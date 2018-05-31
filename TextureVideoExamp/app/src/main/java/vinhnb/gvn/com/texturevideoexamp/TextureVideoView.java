package vinhnb.gvn.com.texturevideoexamp;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.SurfaceTexture;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnCompletionListener;
import android.media.MediaPlayer.OnErrorListener;
import android.media.MediaPlayer.OnInfoListener;
import android.net.Uri;
import android.os.Handler;
import android.util.AttributeSet;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.MediaController;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Displays a video file.  The VideoView class
 * can load images from various sources (such as resources or content
 * providers), takes care of computing its measurement from the video so that
 * it can be used in any layout manager, and provides various display options
 * such as scaling and tinting.<p>
 * <p/>
 * <em>Note: VideoView does not retain its full state when going into the
 * background.</em>  In particular, it does not restore the current play state,
 * play position, selected tracks added via
 * {@link android.app.Activity#onSaveInstanceState} and
 * {@link android.app.Activity#onRestoreInstanceState}.<p>
 * Also note that the audio session id (from {@link #getAudioSessionId}) may
 * change from its previously returned value when the VideoView is restored.
 */


public class TextureVideoView extends android.view.TextureView implements MediaController.MediaPlayerControl
        , Player//thêm
{

    public static final String TAG = "TextureVideoView";
    public static final int VIDEO_BEGINNING = 0;

    /**
     * Notifies periodically about replay.
     */
    public interface ReplayListener {
        /**
         * Called periodically to notify that we are still playing.
         * <p/>
         * Used to check whether we are still permitted to watch.
         */
        void onStillPlaying();
    }

    // We should keep this close to android.widget.MediaController,
    // so that porting a controller to android's VideoView remains manageable.
    public interface VideoController {
        // void setMediaPlayer(MediaController.MediaPlayerControl player);
        void setMediaPlayer(Player player);

        void setEnabled(boolean value);

        void setAnchorView(View view);

        void show(int timeInMilliSeconds);

        void show();

        void hide();

//        // Should not be here!
//        // this should be handled internally, not triggered from @outside the controller.
//        // it is specific to the concert play.
//        void hidePreviousPieceButton();

//        // Should not be here!
//        // this should be handled internally, not triggered from @outside the controller.
//        // it is specific to the concert play.
//        void hideNextPieceButton();
    }

    private static final ReplayListener NULL_REPLAY_LISTENER = new ReplayListener() {
        @Override
        public void onStillPlaying() {
            // no op
        }
    };

    // all possible internal states
    private static final int STATE_ERROR = -1;
    private static final int STATE_IDLE = 0;
    private static final int STATE_PREPARING = 1;
    private static final int STATE_PREPARED = 2;
    private static final int STATE_PLAYING = 3;
    private static final int STATE_PAUSED = 4;
    private static final int STATE_PLAYBACK_COMPLETED = 5;
    private static final int MILLIS_IN_SEC = 1000;
    private static final long NOTIFY_REPLAY_INTERVAL_MILLIS = TimeUnit.MINUTES.toMillis(10);

    // collaborators / delegates / composites .. discuss
    private final VideoSizeCalculator videoSizeCalculator;
    // mCurrentState is a VideoView object's current state.
    // mTargetState is the state that a method caller intends to reach.
    // For instance, regardless the VideoView object's current state,
    // calling pause() intends to bring the object to a target state
    // of STATE_PAUSED.
    private int mCurrentState = STATE_IDLE;

    private int mTargetState = STATE_IDLE;
    // settable by the client
    private Uri mUri;

    private Map<String, String> mHeaders;
    // All the stuff we need for playing and showing a video
    private SurfaceTexture mSurfaceTexture;
    private MediaPlayer mMediaPlayer = null;
    private int mAudioSession;
    //    private MediaController mConcertPlayerController;
    private PlayerController mConcertPlayerController;

    private OnCompletionListener mOnCompletionListener;
    private MediaPlayer.OnPreparedListener mOnPreparedListener;
    private int mCurrentBufferPercentage;
    private OnErrorListener mOnErrorListener;
    private OnInfoListener mOnInfoListener;
    private int mSeekWhenPrepared;  // recording the seek position while preparing
    private boolean mCanPause;
    private boolean mCanSeekBack;
    private boolean mCanSeekForward;
    private OnPlayStateListener onPlayStateListener;
    private ReplayListener replayListener = NULL_REPLAY_LISTENER;
    private final Runnable replayNotifyRunnable = new Runnable() {
        @Override
        public void run() {
            replayListener.onStillPlaying();
            postDelayed(this, NOTIFY_REPLAY_INTERVAL_MILLIS);
        }
    };

    private AlertDialog errorDialog;

    private MediaControllerStateChangeListener mediaControllerStateChangeListener;

    public TextureVideoView(final Context context, final AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public TextureVideoView(final Context context, final AttributeSet attrs, final int defStyle) {
        super(context, attrs, defStyle);
        videoSizeCalculator = new VideoSizeCalculator();
        initVideoView();
    }

    public final void setReplayListener(final ReplayListener replayListener) {
        this.replayListener = replayListener != null ? replayListener : NULL_REPLAY_LISTENER;
    }


    @Override
    protected void onMeasure(final int widthMeasureSpec, final int heightMeasureSpec) {
        //onAttachedToWindow -> measure() -> onMesure() -> layout() -> onLayout() -> dispatchDraw() -> draw()(final method)  -> onDraw() -> user input......
        //user input...... -> requestLayout() -> onAttachedToWindow
        //user input...... -> invalidate() -> onDraw()

        //tính toán (measure) ở lần đầu từ xml
        VideoSizeCalculator.Dimens dimens = videoSizeCalculator.measure(widthMeasureSpec, heightMeasureSpec);
        setMeasuredDimension(dimens.getWidth(), dimens.getHeight());
    }

    @Override
    public void onInitializeAccessibilityEvent(final AccessibilityEvent event) {
        super.onInitializeAccessibilityEvent(event);
        event.setClassName(TextureVideoView.class.getName());
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(final AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName(TextureVideoView.class.getName());
    }

    public int resolveAdjustedSize(final int desiredSize, final int measureSpec) {
        return getDefaultSize(desiredSize, measureSpec);
    }

    private void initVideoView() {
        videoSizeCalculator.setVideoSize(0, 0);
        //lắng nghe khung hình SurfaceTexture
        setSurfaceTextureListener(mSTListener);

        //cho phép focus
        setFocusable(true);

        //forcus in mode Touch (there is no focus and no selection.) ex trackball
        setFocusableInTouchMode(true);

        //cần forcus
        requestFocus();

        mCurrentState = STATE_IDLE;
        mTargetState = STATE_IDLE;

        //set info @outside
        setOnInfoListener(onInfoToPlayStateListener);
    }

    public void setVideoFromBeginning(final String path) {
        setVideo(Uri.parse(path), VIDEO_BEGINNING);
    }

    private void setVideo(final Uri uri, final int seekInSeconds) {
        setVideoURI(uri, null, seekInSeconds);
    }

    public void setVideo(final String url, final int seekInSeconds) {
        setVideoURI(Uri.parse(url), null, seekInSeconds);
    }

    private void setVideoURI(final Uri uri, final Map<String, String> headers, final int seekInSeconds) {
        Log.d(TAG, "start playing: " + uri);
        mUri = uri;
        mHeaders = headers;
        mSeekWhenPrepared = seekInSeconds * 1000;
        openVideo();
        requestLayout();
        invalidate();
    }

    public String getCurrentStream() {
        return mUri.toString();
    }

    public void stopPlayback() {
        if (mMediaPlayer != null) {
            mMediaPlayer.stop();
            mMediaPlayer.release();
            mMediaPlayer = null;
            setKeepScreenOn(false);
            stopPingLoop();
            mCurrentState = STATE_IDLE;
            mTargetState = STATE_IDLE;
        }
    }

    private void openVideo() {
        //check uri
        //check
        if (notReadyForPlaybackJustYetWillTryAgainLater()) {
            return;
        }

        //tạm dừng muscic hệ thống
        tellTheMusicPlaybackServiceToPause();

        // we shouldn't clear the target state, because somebody might have called start() previously
        try {
            //release tài nguyên nhưng ko clear target state
            release(false);
            mMediaPlayer = new MediaPlayer();

            //mỗi thực thể Media có 1 audio seesion sinh ra
            //gán lại session mỗi lần open video
            if (mAudioSession != 0) {
                mMediaPlayer.setAudioSessionId(mAudioSession);
            } else {
                mAudioSession = mMediaPlayer.getAudioSessionId();
            }

            //call back
            mMediaPlayer.setOnPreparedListener(mPreparedListener);//prepared xong video
            mMediaPlayer.setOnVideoSizeChangedListener(mSizeChangedListener);//change size
            mMediaPlayer.setOnCompletionListener(mCompletionListener);//play xong video
            mMediaPlayer.setOnErrorListener(mErrorListener);//error video (ko thể play)
            mMediaPlayer.setOnInfoListener(mInfoListener);//dettech 1 vài trạng thái để thông báo tới người dùng @outside(ko phải bị error)
            mMediaPlayer.setOnBufferingUpdateListener(mBufferingUpdateListener);//khi có buffering mới cập nhật
            mCurrentBufferPercentage = 0;//reset lượng buffer hiện tại nó sẽ tăng khi mBufferingUpdateListener được goi
            mMediaPlayer.setDataSource(getContext(), mUri, mHeaders);//set data, gồm uri, mHeaders cho 1 số định dạng video
            mMediaPlayer.setSurface(new Surface(mSurfaceTexture));// set màn hình surface với frames mSurfaceTexture có được khi onSurfaceTextureAvailable()
            mMediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);//chạy chế độ music
            mMediaPlayer.setScreenOnWhilePlaying(true);//cho phép giữ màn hình khi chạy lại ví dụ như case wakeup
            mMediaPlayer.prepareAsync();//không đồng bộ

            // we don't set the target state here either, but preserve the target state that was there before.
            mCurrentState = STATE_PREPARING;
            attachMediaController();//gắn media controller mConcertPlayerController
        } catch (final IOException ex) {
            notifyUnableToOpenContent(ex);//gửi error với MEDIA_ERROR_UNKNOWN
        } catch (final IllegalArgumentException ex) {
            notifyUnableToOpenContent(ex);
        }
    }

    private boolean notReadyForPlaybackJustYetWillTryAgainLater() {
        return mUri == null || mSurfaceTexture == null;
    }

    private void tellTheMusicPlaybackServiceToPause() {
        // these constants need to be published somewhere in the framework.
        Intent i = new Intent("com.android.music.musicservicecommand");
        i.putExtra("command", "pause");
        getContext().sendBroadcast(i);
    }

    private void notifyUnableToOpenContent(final Exception ex) {
        Log.w(TAG, "Unable to open content: " + mUri, ex);
        mCurrentState = STATE_ERROR;
        mTargetState = STATE_ERROR;
        mErrorListener.onError(mMediaPlayer, MediaPlayer.MEDIA_ERROR_UNKNOWN, 0);
    }


    //    public void setMediaController(final MediaController controller) {
    public void setMediaController(final PlayerController controller) {
        hideMediaController();
        mConcertPlayerController = controller;
        attachMediaController();
    }

    private void attachMediaController() {
        if (mMediaPlayer != null && mConcertPlayerController != null) {
            mConcertPlayerController.setMediaPlayer(this);
            View anchorView = this.getParent() instanceof View ? (View) this.getParent() : this;
            mConcertPlayerController.setAnchorView(anchorView);
            mConcertPlayerController.setEnabled(isInPlaybackState());
        }
    }

    public void setMediaControllerStateChangeListener(MediaControllerStateChangeListener mediaControllerStateChangeListener) {
        this.mediaControllerStateChangeListener = mediaControllerStateChangeListener;
    }

    public interface MediaControllerStateChangeListener {
        void onMediaControllerOpened(MediaController mediaController);

        void onMediaControllerClosed(MediaController mediaController);
    }

    private MediaPlayer.OnVideoSizeChangedListener mSizeChangedListener = new MediaPlayer.OnVideoSizeChangedListener() {
        @Override
        public void onVideoSizeChanged(final MediaPlayer mp, final int width, final int height) {
            //up date video size = mediaPlayer size
            // size có thể = 0
            videoSizeCalculator.setVideoSize(mp.getVideoWidth(), mp.getVideoHeight());
            if (videoSizeCalculator.hasASizeYet()) {
                //nếu size này thực sự #0
                //làm mới lại layout
                requestLayout();
            }
        }
    };

    private MediaPlayer.OnPreparedListener mPreparedListener = new MediaPlayer.OnPreparedListener() {
        @Override
        public void onPrepared(final MediaPlayer mp) {
            mCurrentState = STATE_PREPARED;

            //flag
            mCanPause = true;
            mCanSeekBack = true;
            mCanSeekForward = true;

            //call OnPreparedListener from @outside
            if (mOnPreparedListener != null) {
                mOnPreparedListener.onPrepared(mMediaPlayer);
            }

            //enable
            if (mConcertPlayerController != null) {
                mConcertPlayerController.setEnabled(true);
            }

            //set video size real when load done video
            videoSizeCalculator.setVideoSize(mp.getVideoWidth(), mp.getVideoHeight());

            //mSeekWhenPrepared chagne
            int seekToPosition = mSeekWhenPrepared;  // mSeekWhenPrepared may be changed after seekTo() call
            if (seekToPosition != 0) {
                seekTo(seekToPosition); // if is state can play then seek to, reset mSeekWhenPrepared... else giữ nguyên ko seekto
            }

            if (mTargetState == STATE_PLAYING) {
                //nếu mục đích là chạy thì bắt đầu chạy
                start();
                //show controller sau 3 s
                showMediaController();
            } else if (pausedAt(seekToPosition)) {
                //nesu tạm dừng tại 1 điểm mSeekWhenPrepared
                //thì show media
                //show controller sau 0 s
                showStickyMediaController();
            }
        }
    };

    private boolean pausedAt(final int seekToPosition) {
        return !isPlaying() && (seekToPosition != 0 || getCurrentPosition() > 0);
    }

    private void showStickyMediaController() {
        if (mConcertPlayerController != null) {
            mConcertPlayerController.show(0);
        }
    }


    private OnCompletionListener mCompletionListener = new OnCompletionListener() {
        @Override
        public void onCompletion(final MediaPlayer mp) {
            //ngừng màn hình
            //ngừng thông báo state đang chạy @outside
            setKeepScreenOn(false);
            stopPingLoop();

            mCurrentState = STATE_PLAYBACK_COMPLETED;
            mTargetState = STATE_PLAYBACK_COMPLETED;

            //ẩn media
            hideMediaController();
            if (mOnCompletionListener != null) {
                mOnCompletionListener.onCompletion(mMediaPlayer);
            }
        }
    };


    private OnInfoListener mInfoListener = new OnInfoListener() {
        @Override
        public boolean onInfo(final MediaPlayer mp, final int arg1, final int arg2) {
            if (mOnInfoListener != null) {
                mOnInfoListener.onInfo(mp, arg1, arg2);
            }
            return true;
        }
    };

    private OnErrorListener mErrorListener = new OnErrorListener() {
        @Override
        public boolean onError(final MediaPlayer mp, final int frameworkError, final int implError) {
            Log.d(TAG, "Error: " + frameworkError + "," + implError);
            //nếu state lỗi thì đã được thông báo ngay lúc đó rồi, ko cần xử lý
            if (mCurrentState == STATE_ERROR) {
                return true;
            }

            mCurrentState = STATE_ERROR;
            mTargetState = STATE_ERROR;
            hideMediaController();

            //nếu có lỗi từ source thì thông báo @outside
            //return true chỉ khi có callback đc setup
            if (allowPlayStateToHandle(frameworkError)) {
                return true;
            }

            //nếu lỗi khi play thì thông báo @outside qua callback được setup
            //không bao h return
            if (allowErrorListenerToHandle(frameworkError, implError)) {
                return true;
            }

            //show dialog lỗi
            handleError(frameworkError);
            return true;
        }
    };

    private void hideMediaController() {
        if (mConcertPlayerController != null) {
            mConcertPlayerController.hide();
        }
    }

    private void showMediaController() {
        if (mConcertPlayerController != null) {
            mConcertPlayerController.show();
        }
    }

    private boolean allowPlayStateToHandle(final int frameworkError) {
        if (frameworkError == MediaPlayer.MEDIA_ERROR_UNKNOWN || frameworkError == MediaPlayer.MEDIA_ERROR_IO) {
            Log.e(TAG, "TextureVideoView error. File or network related operation errors.");
            if (hasPlayStateListener()) {
                return onPlayStateListener.onStopWithExternalError(mMediaPlayer.getCurrentPosition() / MILLIS_IN_SEC);
            }
        }
        return false;
    }

    private boolean allowErrorListenerToHandle(final int frameworkError, final int implError) {
        if (mOnErrorListener != null) {
            mOnErrorListener.onError(mMediaPlayer, frameworkError, implError);
        }

        return false;
    }

    private void handleError(final int frameworkError) {
        //show dialog lỗi
        if (getWindowToken() != null) {
            if (errorDialog != null && errorDialog.isShowing()) {
                Log.d(TAG, "Dismissing last error dialog for a new one");
                errorDialog.dismiss();
            }
            errorDialog = createErrorDialog(this.getContext(), mOnCompletionListener, mMediaPlayer, getErrorMessage(frameworkError));
            errorDialog.show();
        }
    }

    private static AlertDialog createErrorDialog(final Context context, final OnCompletionListener completionListener, final MediaPlayer mediaPlayer, final int errorMessage) {
        return new AlertDialog.Builder(context)
                .setMessage(errorMessage)
                .setPositiveButton(android.R.string.ok,
                        new DialogInterface.OnClickListener() {
                            public void onClick(final DialogInterface dialog, final int whichButton) {
                                /* If we get here, there is no onError listener, so
                                 * at least inform them that the video is over.
                                 */
                                if (completionListener != null) {
                                    completionListener.onCompletion(mediaPlayer);
                                }
                            }
                        }
                )
                .setCancelable(false)
                .create();
    }

    private static int getErrorMessage(final int frameworkError) {
        int messageId = R.string.play_error_message;

        if (frameworkError == MediaPlayer.MEDIA_ERROR_IO) {
            Log.e(TAG, "TextureVideoView error. File or network related operation errors.");
        } else if (frameworkError == MediaPlayer.MEDIA_ERROR_MALFORMED) {
            Log.e(TAG, "TextureVideoView error. Bitstream is not conforming to the related coding standard or file spec.");
        } else if (frameworkError == MediaPlayer.MEDIA_ERROR_SERVER_DIED) {
            Log.e(TAG, "TextureVideoView error. Media server died. In this case, the application must release the MediaPlayer object and instantiate a new one.");
        } else if (frameworkError == MediaPlayer.MEDIA_ERROR_TIMED_OUT) {
            Log.e(TAG, "TextureVideoView error. Some operation takes too long to complete, usually more than 3-5 seconds.");
        } else if (frameworkError == MediaPlayer.MEDIA_ERROR_UNKNOWN) {
            Log.e(TAG, "TextureVideoView error. Unspecified media player error.");
        } else if (frameworkError == MediaPlayer.MEDIA_ERROR_UNSUPPORTED) {
            Log.e(TAG, "TextureVideoView error. Bitstream is conforming to the related coding standard or file spec, but the media framework does not support the feature.");
        } else if (frameworkError == MediaPlayer.MEDIA_ERROR_NOT_VALID_FOR_PROGRESSIVE_PLAYBACK) {
            Log.e(TAG, "TextureVideoView error. The video is streamed and its container is not valid for progressive playback i.e the video's index (e.g moov atom) is not at the start of the file.");
            messageId = R.string.play_progressive_error_message;
        }
        return messageId;
    }

    private MediaPlayer.OnBufferingUpdateListener mBufferingUpdateListener = new MediaPlayer.OnBufferingUpdateListener() {
        @Override
        public void onBufferingUpdate(final MediaPlayer mp, final int percent) {
            mCurrentBufferPercentage = percent;
        }
    };

    /**
     * Register a callback to be invoked when the media file
     * is loaded and ready to go.
     *
     * @param l The callback that will be run
     */
    public void setOnPreparedListener(final MediaPlayer.OnPreparedListener l) {
        mOnPreparedListener = l;
    }

    /**
     * Register a callback to be invoked when the end of a media file
     * has been reached during playback.
     *
     * @param l The callback that will be run
     */
    public void setOnCompletionListener(final OnCompletionListener l) {
        mOnCompletionListener = l;
    }

    /**
     * Register a callback to be invoked when an error occurs
     * during playback or setup.  If no listener is specified,
     * or if the listener returned false, VideoView will inform
     * the user of any errors.
     *
     * @param l The callback that will be run
     */
    public void setOnErrorListener(final OnErrorListener l) {
        mOnErrorListener = l;
    }

    /**
     * Register a callback to be invoked when an informational event
     * occurs during playback or setup.
     *
     * @param l The callback that will be run
     */
    private void setOnInfoListener(final OnInfoListener l) {
        mOnInfoListener = l;
    }

    private SurfaceTextureListener mSTListener = new SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(final SurfaceTexture surface, final int width, final int height) {
            //mSurfaceTexture có thể bị null nếu chưa attach window
            mSurfaceTexture = surface;

            //những thứ cài đặt chi tiết chỉ có thể thực hiện sau khi onSurfaceTextureAvailable ok
            openVideo();
        }

        @Override
        public void onSurfaceTextureSizeChanged(final SurfaceTexture surface, final int width, final int height) {
            //when setOnVideoSizeChangedListener() then videoSizeCalculator đã được update setVideoSize và requestLayout
            //ở đây khi SurfaceTexture change
            //check xem state target có phải STATE_PLAYING (đã prepared done) tức đã gọi startVideo()
            boolean isValidState = (mTargetState == STATE_PLAYING);
            //check xem size SurfaceTexture có thực đúng = size của video của setOnVideoSizeChangedListener()
            boolean hasValidSize = videoSizeCalculator.currentSizeIs(width, height);

            //nếu OK tất cả thì đã sẵn sàng start() tại thời điểm mSeekWhenPrepared
            if (mMediaPlayer != null && isValidState && hasValidSize) {
                if (mSeekWhenPrepared != 0) {
                    seekTo(mSeekWhenPrepared);
                }
                start();
            }
        }

        @Override
        public boolean onSurfaceTextureDestroyed(final SurfaceTexture surface) {
            //release
            mSurfaceTexture = null;
            hideMediaController();
            release(true);
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(final SurfaceTexture surface) {
            //update SurfaceTexture = SurfaceTexture hiện tại
            mSurfaceTexture = surface;
        }
    };

    /*
     * release the media player in any state
     */
    private void release(final boolean clearTargetState) {
        if (mMediaPlayer != null) {
            mMediaPlayer.reset();
            mMediaPlayer.release();
            mMediaPlayer = null;
            mCurrentState = STATE_IDLE;
            if (clearTargetState) {
                mTargetState = STATE_IDLE;
            }
        }
    }

    @Override
    public boolean onTouchEvent(final MotionEvent ev) {
        if (isInPlaybackState() && mConcertPlayerController != null) {
            mConcertPlayerController.show();
        }
        return false;
    }

    @Override
    public boolean onTrackballEvent(final MotionEvent ev) {
        if (isInPlaybackState() && mConcertPlayerController != null) {
            mConcertPlayerController.show();
        }
        return false;
    }

    /**
     * xử lý khi kết hợp với các ngữ cảnh mà chắc chắn người dùng sẽ muốn app sẽ làm
     *
     * @param keyCode
     * @param event
     * @return
     */
    @Override
    public boolean onKeyDown(final int keyCode, final KeyEvent event) {
        boolean isKeyCodeSupported = keyCode != KeyEvent.KEYCODE_BACK &&
                keyCode != KeyEvent.KEYCODE_VOLUME_UP &&
                keyCode != KeyEvent.KEYCODE_VOLUME_DOWN &&
                keyCode != KeyEvent.KEYCODE_VOLUME_MUTE &&
                keyCode != KeyEvent.KEYCODE_MENU &&
                keyCode != KeyEvent.KEYCODE_CALL &&
                keyCode != KeyEvent.KEYCODE_ENDCALL;
        if (isInPlaybackState() && isKeyCodeSupported && mConcertPlayerController != null) {
            if (keyCode == KeyEvent.KEYCODE_HEADSETHOOK || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                if (mMediaPlayer.isPlaying()) {
                    pause();
                    showMediaController();
                } else {
                    start();
                    hideMediaController();
                }
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY) {
                if (!mMediaPlayer.isPlaying()) {
                    start();
                    hideMediaController();
                }
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_MEDIA_STOP || keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE) {
                if (mMediaPlayer.isPlaying()) {
                    pause();
                    showMediaController();
                }
                return true;
            } else {
                mConcertPlayerController.show();
            }
        }

        return super.onKeyDown(keyCode, event);
    }

    //region MediaController.MediaPlayerControl
    @Override
    public void start() {
        if (isInPlaybackState()) {
            //nếu hiện tại đang ở state cho phép play
            //thì start
            mMediaPlayer.start();

            //tiếp tục màn hình
            setKeepScreenOn(true);
            mCurrentState = STATE_PLAYING;
        }

        mTargetState = STATE_PLAYING;
        startPingLoop();
    }

    private void startPingLoop() {
        //lấy handler của view
        //nếu null thì không thể update lên UI
        //then return
        final Handler handler = getHandler();
        if (handler == null) {
            return;
        }

        //refresh call back
        //call back : 10s thì update 1 lần về việc vẫn đang chạy ra ngoài @outside
        handler.removeCallbacks(replayNotifyRunnable);
        handler.post(replayNotifyRunnable);
    }

    private void stopPingLoop() {
        final Handler handler = getHandler();
        if (handler == null) {
            return;
        }
        handler.removeCallbacks(replayNotifyRunnable);
    }

    @Override
    public void pause() {
        if (isInPlaybackState()) {
            if (mMediaPlayer.isPlaying()) {
                mMediaPlayer.pause();
                mCurrentState = STATE_PAUSED;
                setKeepScreenOn(false);
            }
        }
        mTargetState = STATE_PAUSED;
        stopPingLoop();
    }

    public void suspend() {
        release(false);
    }

    public void resume() {
        openVideo();
    }

    @Override
    public int getDuration() {
        if (isInPlaybackState()) {
            return mMediaPlayer.getDuration();
        }

        return -1;
    }

    /**
     * @return current position in milliseconds
     */
    @Override
    public int getCurrentPosition() {
        if (isInPlaybackState()) {
            return mMediaPlayer.getCurrentPosition();
        }
        return 0;
    }

    public int getCurrentPositionInSeconds() {
        return getCurrentPosition() / MILLIS_IN_SEC;
    }

    @Override
    public void seekTo(final int millis) {
        if (isInPlaybackState()) {
            mMediaPlayer.seekTo(millis);
            mSeekWhenPrepared = 0;
        } else {
            mSeekWhenPrepared = millis;
        }
    }

    public void seekToSeconds(final int seconds) {
        seekTo(seconds * MILLIS_IN_SEC);
        mMediaPlayer.setOnSeekCompleteListener(new MediaPlayer.OnSeekCompleteListener() {
            @Override
            public void onSeekComplete(final MediaPlayer mp) {
                Log.i(TAG, "seek completed");
            }
        });
    }

    @Override
    public boolean isPlaying() {
        return isInPlaybackState() && mMediaPlayer.isPlaying();
    }

    @Override
    public int getBufferPercentage() {
        if (mMediaPlayer != null) {
            return mCurrentBufferPercentage;
        }
        return 0;
    }

    private boolean isInPlaybackState() {
        return (mMediaPlayer != null &&
                mCurrentState != STATE_ERROR &&
                mCurrentState != STATE_IDLE &&
                mCurrentState != STATE_PREPARING);
    }

    @Override
    public boolean canPause() {
        return mCanPause;
    }

    @Override
    public boolean canSeekBackward() {
        return mCanSeekBack;
    }

    @Override
    public boolean canSeekForward() {
        return mCanSeekForward;
    }

    @Override
    public int getAudioSessionId() {
        if (mAudioSession == 0) {
            MediaPlayer foo = new MediaPlayer();
            mAudioSession = foo.getAudioSessionId();
            foo.release();
        }
        return mAudioSession;
    }
    //endregion

    private final OnInfoListener onInfoToPlayStateListener = new OnInfoListener() {

        @Override
        public boolean onInfo(final MediaPlayer mp, final int what, final int extra) {
            if (noPlayStateListener()) {
                //nếu ko có setup giao diện @outside từ bên ngoài
                return false;
            }

            if (MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START == what) {
                //first video frame for rendering
                onPlayStateListener.onFirstVideoFrameRendered();
                onPlayStateListener.onPlay();
            }
            if (MediaPlayer.MEDIA_INFO_BUFFERING_START == what) {
                //when video tạm dừng phát nội bộ để tiếp nhận thêm buffer mới
                onPlayStateListener.onBuffer();
            }
            if (MediaPlayer.MEDIA_INFO_BUFFERING_END == what) {
                //when ok buffer mới thì thông báo play trở lại
                onPlayStateListener.onPlay();
            }

            return false;
        }
    };

    private boolean noPlayStateListener() {
        return !hasPlayStateListener();
    }

    private boolean hasPlayStateListener() {
        return onPlayStateListener != null;
    }

    public void setOnPlayStateListener(final OnPlayStateListener onPlayStateListener) {
        this.onPlayStateListener = onPlayStateListener;
    }

    public interface OnPlayStateListener {
        void onFirstVideoFrameRendered();

        void onPlay();

        void onBuffer();

        boolean onStopWithExternalError(int position);
    }

}
