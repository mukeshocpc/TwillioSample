package indiqube.com.twilliotest

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.os.CountDownTimer
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar
import com.twilio.audioswitch.selection.AudioDevice
import com.twilio.audioswitch.selection.AudioDevice.*
import com.twilio.audioswitch.selection.AudioDeviceSelector
import com.twilio.video.*
import kotlinx.android.synthetic.main.activity_video.*
import kotlinx.android.synthetic.main.content_video.*
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import java.util.*
import kotlin.properties.Delegates


class VideoActivity : AppCompatActivity() {
    private val CAMERA_MIC_PERMISSION_REQUEST_CODE = 1
    private val TAG = "VideoActivity"
    private val client = OkHttpClient()
    private var TIME: Long = 30000;
    /*
     * You must provide a Twilio Access Token to connect to the Video service
     */
    private val ACCESS_TOKEN_SERVER = "https://web.people-app.org/v1/api/user/token?room="

    private lateinit var accessToken: String
    private lateinit var roomToConnect: String

    private var isParticipant: Boolean = false
    private var memberscount: Int = 0
    val timer = object : CountDownTimer(TIME, 1000) {
        override fun onTick(millisUntilFinished: Long) {

        }

        override fun onFinish() {

            try {
//                if (!isParticipant) {
//                    showToast("Room Connect timout")
//                    onBackPressed()
//                }
            } catch (e: Exception) {

            }


        }
    }

    /*
     * A Room represents communication between a local participant and one or more participants.
     */
    private var room: Room? = null
    private var localParticipant: LocalParticipant? = null

    /*
     * AudioCodec and VideoCodec represent the preferred codec for encoding and decoding audio and
     * video.
     */
    private val audioCodec: AudioCodec
        get() {
            val audioCodecName = sharedPreferences.getString(
                SettingsActivity.PREF_AUDIO_CODEC,
                SettingsActivity.PREF_AUDIO_CODEC_DEFAULT
            )

            return when (audioCodecName) {
                IsacCodec.NAME -> IsacCodec()
                OpusCodec.NAME -> OpusCodec()
                PcmaCodec.NAME -> PcmaCodec()
                PcmuCodec.NAME -> PcmuCodec()
                G722Codec.NAME -> G722Codec()
                else -> OpusCodec()
            }
        }
    private val videoCodec: VideoCodec
        get() {
            val videoCodecName = sharedPreferences.getString(
                SettingsActivity.PREF_VIDEO_CODEC,
                SettingsActivity.PREF_VIDEO_CODEC_DEFAULT
            )

            return when (videoCodecName) {
                Vp8Codec.NAME -> {
                    val simulcast = sharedPreferences.getBoolean(
                        SettingsActivity.PREF_VP8_SIMULCAST,
                        SettingsActivity.PREF_VP8_SIMULCAST_DEFAULT
                    )
                    Vp8Codec(simulcast)
                }
                H264Codec.NAME -> H264Codec()
                Vp9Codec.NAME -> Vp9Codec()
                else -> Vp8Codec()
            }
        }

    private val enableAutomaticSubscription: Boolean
        get() {
            return sharedPreferences.getBoolean(
                SettingsActivity.PREF_ENABLE_AUTOMATIC_SUBSCRIPTION,
                SettingsActivity.PREF_ENABLE_AUTOMATIC_SUBCRIPTION_DEFAULT
            )
        }

    /*
     * Encoding parameters represent the sender side bandwidth constraints.
     */
    private val encodingParameters: EncodingParameters
        get() {
            val defaultMaxAudioBitrate = SettingsActivity.PREF_SENDER_MAX_AUDIO_BITRATE_DEFAULT
            val defaultMaxVideoBitrate = SettingsActivity.PREF_SENDER_MAX_VIDEO_BITRATE_DEFAULT
            val maxAudioBitrate = Integer.parseInt(
                sharedPreferences.getString(
                    SettingsActivity.PREF_SENDER_MAX_AUDIO_BITRATE,
                    defaultMaxAudioBitrate
                ) ?: defaultMaxAudioBitrate
            )
            val maxVideoBitrate = Integer.parseInt(
                sharedPreferences.getString(
                    SettingsActivity.PREF_SENDER_MAX_VIDEO_BITRATE,
                    defaultMaxVideoBitrate
                ) ?: defaultMaxVideoBitrate
            )

            return EncodingParameters(maxAudioBitrate, maxVideoBitrate)
        }

    /*
     * Room events listener
     */
    private val roomListener = object : Room.Listener {
        override fun onConnected(room: Room) {
            localParticipant = room.localParticipant
            videoStatusTextView.text = "Connected to ${room.name}"
            showToast(videoStatusTextView.text.toString())
            title = room.name

            // Only one participant is supported
            room.remoteParticipants?.firstOrNull()?.let { addRemoteParticipant(it) }
        }

        override fun onReconnected(room: Room) {
            videoStatusTextView.text = "Connected to ${room.name}"
            showToast(videoStatusTextView.text.toString())
            reconnectingProgressBar.visibility = View.GONE;
        }

        override fun onReconnecting(room: Room, twilioException: TwilioException) {
            videoStatusTextView.text = "Reconnecting to ${room.name}"
            showToast(videoStatusTextView.text.toString())
            reconnectingProgressBar.visibility = View.VISIBLE;
        }

        override fun onConnectFailure(room: Room, e: TwilioException) {
            videoStatusTextView.text = "Failed to connect"
            showToast(videoStatusTextView.text.toString())
            try {
                audioDeviceSelector.deactivate()
            } catch (e: Exception) {
            }

            initializeUI()
        }

        override fun onDisconnected(room: Room, e: TwilioException?) {
            localParticipant = null
            videoStatusTextView.text = "Disconnected from ${room.name}"
            showToast(videoStatusTextView.text.toString())
            reconnectingProgressBar.visibility = View.GONE;
            this@VideoActivity.room = null
            // Only reinitialize the UI if disconnect was not called from onDestroy()
            if (!disconnectedFromOnDestroy) {
                try {
                    audioDeviceSelector.deactivate()
                } catch (e: Exception) {
                }
                initializeUI()
                moveLocalVideoToPrimaryView()
            }
        }

        override fun onParticipantConnected(room: Room, participant: RemoteParticipant) {
            addRemoteParticipant(participant)
        }

        override fun onParticipantDisconnected(room: Room, participant: RemoteParticipant) {
            removeRemoteParticipant(participant)
        }

        override fun onRecordingStarted(room: Room) {
            /*
             * Indicates when media shared to a Room is being recorded. Note that
             * recording is only available in our Group Rooms developer preview.
             */
            Log.d(TAG, "onRecordingStarted")
        }

        override fun onRecordingStopped(room: Room) {
            /*
             * Indicates when media shared to a Room is no longer being recorded. Note that
             * recording is only available in our Group Rooms developer preview.
             */
            Log.d(TAG, "onRecordingStopped")
        }
    }

    /*
     * RemoteParticipant events listener
     */
    private val participantListener = object : RemoteParticipant.Listener {
        override fun onAudioTrackPublished(
            remoteParticipant: RemoteParticipant,
            remoteAudioTrackPublication: RemoteAudioTrackPublication
        ) {
            Log.i(
                TAG, "onAudioTrackPublished: " +
                        "[RemoteParticipant: identity=${remoteParticipant.identity}], " +
                        "[RemoteAudioTrackPublication: sid=${remoteAudioTrackPublication.trackSid}, " +
                        "enabled=${remoteAudioTrackPublication.isTrackEnabled}, " +
                        "subscribed=${remoteAudioTrackPublication.isTrackSubscribed}, " +
                        "name=${remoteAudioTrackPublication.trackName}]"
            )
            videoStatusTextView.text = "onAudioTrackAdded"
            showToast(videoStatusTextView.text.toString())
        }

        override fun onAudioTrackUnpublished(
            remoteParticipant: RemoteParticipant,
            remoteAudioTrackPublication: RemoteAudioTrackPublication
        ) {
            Log.i(
                TAG, "onAudioTrackUnpublished: " +
                        "[RemoteParticipant: identity=${remoteParticipant.identity}], " +
                        "[RemoteAudioTrackPublication: sid=${remoteAudioTrackPublication.trackSid}, " +
                        "enabled=${remoteAudioTrackPublication.isTrackEnabled}, " +
                        "subscribed=${remoteAudioTrackPublication.isTrackSubscribed}, " +
                        "name=${remoteAudioTrackPublication.trackName}]"
            )
            videoStatusTextView.text = "onAudioTrackRemoved"

            showToast(videoStatusTextView.text.toString())
        }

        override fun onDataTrackPublished(
            remoteParticipant: RemoteParticipant,
            remoteDataTrackPublication: RemoteDataTrackPublication
        ) {
            Log.i(
                TAG, "onDataTrackPublished: " +
                        "[RemoteParticipant: identity=${remoteParticipant.identity}], " +
                        "[RemoteDataTrackPublication: sid=${remoteDataTrackPublication.trackSid}, " +
                        "enabled=${remoteDataTrackPublication.isTrackEnabled}, " +
                        "subscribed=${remoteDataTrackPublication.isTrackSubscribed}, " +
                        "name=${remoteDataTrackPublication.trackName}]"
            )
            videoStatusTextView.text = "onDataTrackPublished"
            showToast(videoStatusTextView.text.toString())
        }

        override fun onDataTrackUnpublished(
            remoteParticipant: RemoteParticipant,
            remoteDataTrackPublication: RemoteDataTrackPublication
        ) {
            Log.i(
                TAG, "onDataTrackUnpublished: " +
                        "[RemoteParticipant: identity=${remoteParticipant.identity}], " +
                        "[RemoteDataTrackPublication: sid=${remoteDataTrackPublication.trackSid}, " +
                        "enabled=${remoteDataTrackPublication.isTrackEnabled}, " +
                        "subscribed=${remoteDataTrackPublication.isTrackSubscribed}, " +
                        "name=${remoteDataTrackPublication.trackName}]"
            )
            videoStatusTextView.text = "onDataTrackUnpublished"
            showToast(videoStatusTextView.text.toString())
        }

        override fun onVideoTrackPublished(
            remoteParticipant: RemoteParticipant,
            remoteVideoTrackPublication: RemoteVideoTrackPublication
        ) {
            Log.i(
                TAG, "onVideoTrackPublished: " +
                        "[RemoteParticipant: identity=${remoteParticipant.identity}], " +
                        "[RemoteVideoTrackPublication: sid=${remoteVideoTrackPublication.trackSid}, " +
                        "enabled=${remoteVideoTrackPublication.isTrackEnabled}, " +
                        "subscribed=${remoteVideoTrackPublication.isTrackSubscribed}, " +
                        "name=${remoteVideoTrackPublication.trackName}]"
            )
            videoStatusTextView.text = "onVideoTrackPublished"
            isParticipant = true
            showToast(videoStatusTextView.text.toString())
        }

        override fun onVideoTrackUnpublished(
            remoteParticipant: RemoteParticipant,
            remoteVideoTrackPublication: RemoteVideoTrackPublication
        ) {
            Log.i(
                TAG, "onVideoTrackUnpublished: " +
                        "[RemoteParticipant: identity=${remoteParticipant.identity}], " +
                        "[RemoteVideoTrackPublication: sid=${remoteVideoTrackPublication.trackSid}, " +
                        "enabled=${remoteVideoTrackPublication.isTrackEnabled}, " +
                        "subscribed=${remoteVideoTrackPublication.isTrackSubscribed}, " +
                        "name=${remoteVideoTrackPublication.trackName}]"
            )
            videoStatusTextView.text = "onVideoTrackUnpublished"
            showToast(videoStatusTextView.text.toString())
        }

        override fun onAudioTrackSubscribed(
            remoteParticipant: RemoteParticipant,
            remoteAudioTrackPublication: RemoteAudioTrackPublication,
            remoteAudioTrack: RemoteAudioTrack
        ) {
            Log.i(
                TAG, "onAudioTrackSubscribed: " +
                        "[RemoteParticipant: identity=${remoteParticipant.identity}], " +
                        "[RemoteAudioTrack: enabled=${remoteAudioTrack.isEnabled}, " +
                        "playbackEnabled=${remoteAudioTrack.isPlaybackEnabled}, " +
                        "name=${remoteAudioTrack.name}]"
            )
            videoStatusTextView.text = "onAudioTrackSubscribed"
            isParticipant = true
            showToast(videoStatusTextView.text.toString())
        }

        override fun onAudioTrackUnsubscribed(
            remoteParticipant: RemoteParticipant,
            remoteAudioTrackPublication: RemoteAudioTrackPublication,
            remoteAudioTrack: RemoteAudioTrack
        ) {
            Log.i(
                TAG, "onAudioTrackUnsubscribed: " +
                        "[RemoteParticipant: identity=${remoteParticipant.identity}], " +
                        "[RemoteAudioTrack: enabled=${remoteAudioTrack.isEnabled}, " +
                        "playbackEnabled=${remoteAudioTrack.isPlaybackEnabled}, " +
                        "name=${remoteAudioTrack.name}]"
            )
            videoStatusTextView.text = "onAudioTrackUnsubscribed"
            showToast(videoStatusTextView.text.toString())
        }

        override fun onAudioTrackSubscriptionFailed(
            remoteParticipant: RemoteParticipant,
            remoteAudioTrackPublication: RemoteAudioTrackPublication,
            twilioException: TwilioException
        ) {
            Log.i(
                TAG, "onAudioTrackSubscriptionFailed: " +
                        "[RemoteParticipant: identity=${remoteParticipant.identity}], " +
                        "[RemoteAudioTrackPublication: sid=${remoteAudioTrackPublication.trackSid}, " +
                        "name=${remoteAudioTrackPublication.trackName}]" +
                        "[TwilioException: code=${twilioException.code}, " +
                        "message=${twilioException.message}]"
            )
            videoStatusTextView.text = "onAudioTrackSubscriptionFailed"
            showToast(videoStatusTextView.text.toString())
        }

        override fun onDataTrackSubscribed(
            remoteParticipant: RemoteParticipant,
            remoteDataTrackPublication: RemoteDataTrackPublication,
            remoteDataTrack: RemoteDataTrack
        ) {
            Log.i(
                TAG, "onDataTrackSubscribed: " +
                        "[RemoteParticipant: identity=${remoteParticipant.identity}], " +
                        "[RemoteDataTrack: enabled=${remoteDataTrack.isEnabled}, " +
                        "name=${remoteDataTrack.name}]"
            )
            videoStatusTextView.text = "onDataTrackSubscribed"
            showToast(videoStatusTextView.text.toString())
        }

        override fun onDataTrackUnsubscribed(
            remoteParticipant: RemoteParticipant,
            remoteDataTrackPublication: RemoteDataTrackPublication,
            remoteDataTrack: RemoteDataTrack
        ) {
            Log.i(
                TAG, "onDataTrackUnsubscribed: " +
                        "[RemoteParticipant: identity=${remoteParticipant.identity}], " +
                        "[RemoteDataTrack: enabled=${remoteDataTrack.isEnabled}, " +
                        "name=${remoteDataTrack.name}]"
            )
            videoStatusTextView.text = "onDataTrackUnsubscribed"
            showToast(videoStatusTextView.text.toString())
        }

        override fun onDataTrackSubscriptionFailed(
            remoteParticipant: RemoteParticipant,
            remoteDataTrackPublication: RemoteDataTrackPublication,
            twilioException: TwilioException
        ) {
            Log.i(
                TAG, "onDataTrackSubscriptionFailed: " +
                        "[RemoteParticipant: identity=${remoteParticipant.identity}], " +
                        "[RemoteDataTrackPublication: sid=${remoteDataTrackPublication.trackSid}, " +
                        "name=${remoteDataTrackPublication.trackName}]" +
                        "[TwilioException: code=${twilioException.code}, " +
                        "message=${twilioException.message}]"
            )
            videoStatusTextView.text = "onDataTrackSubscriptionFailed"
            showToast(videoStatusTextView.text.toString())
        }

        override fun onVideoTrackSubscribed(
            remoteParticipant: RemoteParticipant,
            remoteVideoTrackPublication: RemoteVideoTrackPublication,
            remoteVideoTrack: RemoteVideoTrack
        ) {
            Log.i(
                TAG, "onVideoTrackSubscribed: " +
                        "[RemoteParticipant: identity=${remoteParticipant.identity}], " +
                        "[RemoteVideoTrack: enabled=${remoteVideoTrack.isEnabled}, " +
                        "name=${remoteVideoTrack.name}]"
            )
            videoStatusTextView.text = "onVideoTrackSubscribed"
            showToast(videoStatusTextView.text.toString())
            addRemoteParticipantVideo(remoteVideoTrack)
        }

        override fun onVideoTrackUnsubscribed(
            remoteParticipant: RemoteParticipant,
            remoteVideoTrackPublication: RemoteVideoTrackPublication,
            remoteVideoTrack: RemoteVideoTrack
        ) {
            Log.i(
                TAG, "onVideoTrackUnsubscribed: " +
                        "[RemoteParticipant: identity=${remoteParticipant.identity}], " +
                        "[RemoteVideoTrack: enabled=${remoteVideoTrack.isEnabled}, " +
                        "name=${remoteVideoTrack.name}]"
            )
            videoStatusTextView.text = "onVideoTrackUnsubscribed"
            removeParticipantVideo(remoteVideoTrack)
            showToast("Call Disconnected")
            onBackPressed()
        }

        override fun onVideoTrackSubscriptionFailed(
            remoteParticipant: RemoteParticipant,
            remoteVideoTrackPublication: RemoteVideoTrackPublication,
            twilioException: TwilioException
        ) {
            Log.i(
                TAG, "onVideoTrackSubscriptionFailed: " +
                        "[RemoteParticipant: identity=${remoteParticipant.identity}], " +
                        "[RemoteVideoTrackPublication: sid=${remoteVideoTrackPublication.trackSid}, " +
                        "name=${remoteVideoTrackPublication.trackName}]" +
                        "[TwilioException: code=${twilioException.code}, " +
                        "message=${twilioException.message}]"
            )
            videoStatusTextView.text = "onVideoTrackSubscriptionFailed"
            Snackbar.make(
                connectActionFab,
                "Failed to subscribe to ${remoteParticipant.identity}",
                Snackbar.LENGTH_LONG
            )
                .show()
        }

        override fun onAudioTrackEnabled(
            remoteParticipant: RemoteParticipant,
            remoteAudioTrackPublication: RemoteAudioTrackPublication
        ) {
        }

        override fun onVideoTrackEnabled(
            remoteParticipant: RemoteParticipant,
            remoteVideoTrackPublication: RemoteVideoTrackPublication
        ) {
        }

        override fun onVideoTrackDisabled(
            remoteParticipant: RemoteParticipant,
            remoteVideoTrackPublication: RemoteVideoTrackPublication
        ) {
        }

        override fun onAudioTrackDisabled(
            remoteParticipant: RemoteParticipant,
            remoteAudioTrackPublication: RemoteAudioTrackPublication
        ) {
        }
    }

    private var localAudioTrack: LocalAudioTrack? = null
    private var localVideoTrack: LocalVideoTrack? = null
    private var alertDialog: AlertDialog? = null
    private val cameraCapturerCompat by lazy {
        CameraCapturerCompat(this, getAvailableCameraSource())
    }
    private val sharedPreferences by lazy {
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(this@VideoActivity)
    }

    /*
     * Audio management
     */
    private val audioDeviceSelector by lazy {
        AudioDeviceSelector(applicationContext)
    }
    private var savedVolumeControlStream by Delegates.notNull<Int>()
    private lateinit var audioDeviceMenuItem: MenuItem

    private var participantIdentity: String? = null
    private lateinit var localVideoView: VideoRenderer
    private var disconnectedFromOnDestroy = false
    private var isSpeakerPhoneEnabled = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video)
        roomToConnect = intent!!.getStringExtra("room");
        /*
         * Set local video view to primary view
         */
        localVideoView = primaryVideoView

        /*
         * Enable changing the volume using the up/down keys during a conversation
         */
        savedVolumeControlStream = volumeControlStream
        volumeControlStream = AudioManager.STREAM_VOICE_CALL

        /*
         * Set access token
         */

        /*
         * Request permissions.
         */
        requestPermissionForCameraAndMicrophone()

        /*
         * Set the initial state of the UI
         */
        initializeUI()
        timer.start()
        retrieveAccessTokenfromServer(roomToConnect)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        if (requestCode == CAMERA_MIC_PERMISSION_REQUEST_CODE) {
            var cameraAndMicPermissionGranted = true

            for (grantResult in grantResults) {
                cameraAndMicPermissionGranted = cameraAndMicPermissionGranted and
                        (grantResult == PackageManager.PERMISSION_GRANTED)
            }

            if (cameraAndMicPermissionGranted) {
                createAudioAndVideoTracks()
            } else {
                Toast.makeText(
                    this,
                    R.string.permissions_needed,
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        /*
         * If the local video track was released when the app was put in the background, recreate.
         */
        localVideoTrack = if (localVideoTrack == null && checkPermissionForCameraAndMicrophone()) {
            LocalVideoTrack.create(
                this,
                false,
                cameraCapturerCompat.videoCapturer
            )
        } else {
            localVideoTrack
        }
        localVideoTrack?.addRenderer(localVideoView)

        /*
         * If connected to a Room then share the local video track.
         */
        localVideoTrack?.let { localParticipant?.publishTrack(it) }

        /*
         * Update encoding parameters if they have changed.
         */
        localParticipant?.setEncodingParameters(encodingParameters)

        /*
         * Update reconnecting UI
         */
        room?.let {
            reconnectingProgressBar.visibility = if (it.state != Room.State.RECONNECTING)
                View.GONE else
                View.VISIBLE
            videoStatusTextView.text = "Connected to ${it.name}"
        }
    }

    override fun onPause() {
        /*
         * If this local video track is being shared in a Room, remove from local
         * participant before releasing the video track. Participants will be notified that
         * the track has been removed.
         */
        localVideoTrack?.let { localParticipant?.unpublishTrack(it) }

        /*
         * Release the local video track before going in the background. This ensures that the
         * camera can be used by other applications while this app is in the background.
         */
        localVideoTrack?.release()
        localVideoTrack = null
        super.onPause()
    }

    override fun onDestroy() {
        /*
         * Tear down audio management and restore previous volume stream
         */

        try {
            audioDeviceSelector.stop()
        } catch (e: Exception) {
        }

        volumeControlStream = savedVolumeControlStream

        /*
         * Always disconnect from the room before leaving the Activity to
         * ensure any memory allocated to the Room resource is freed.
         */
        room?.disconnect()
        disconnectedFromOnDestroy = true

        /*
         * Release the local audio and video tracks ensuring any memory allocated to audio
         * or video is freed.
         */
        localAudioTrack?.release()
        localVideoTrack?.release()

        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val inflater = menuInflater
        inflater.inflate(R.menu.menu, menu)
        audioDeviceMenuItem = menu.findItem(R.id.menu_audio_device)

        /*
         * Start the audio device selector after the menu is created and update the icon when the
         * selected audio device changes.
         */
        audioDeviceSelector.start { audioDevices, audioDevice ->

            updateAudioDeviceIcon(audioDevice)
        }

        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_settings -> startActivity(Intent(this, SettingsActivity::class.java))
            R.id.menu_audio_device -> try {
                showAudioDevices()
            } catch (e: Exception) {
            }
        }
        return true
    }

    private fun requestPermissionForCameraAndMicrophone() {
        if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA) ||
            ActivityCompat.shouldShowRequestPermissionRationale(
                this,
                Manifest.permission.RECORD_AUDIO
            )
        ) {
            Toast.makeText(
                this,
                R.string.permissions_needed,
                Toast.LENGTH_LONG
            ).show()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
                CAMERA_MIC_PERMISSION_REQUEST_CODE
            )
        }
    }

    private fun checkPermissionForCameraAndMicrophone(): Boolean {
        val resultCamera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
        val resultMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)

        return resultCamera == PackageManager.PERMISSION_GRANTED &&
                resultMic == PackageManager.PERMISSION_GRANTED
    }

    private fun createAudioAndVideoTracks() {
        // Share your microphone
        localAudioTrack = LocalAudioTrack.create(this, true)

        // Share your camera
        localVideoTrack = LocalVideoTrack.create(
            this,
            false,
            cameraCapturerCompat.videoCapturer
        )
    }

    private fun getAvailableCameraSource(): CameraCapturer.CameraSource {
        return if (CameraCapturer.isSourceAvailable(CameraCapturer.CameraSource.FRONT_CAMERA))
            CameraCapturer.CameraSource.FRONT_CAMERA
        else
            CameraCapturer.CameraSource.BACK_CAMERA
    }


    private fun retrieveAccessTokenfromServer(roomName: String) {


        val request = Request.Builder()
            .url(ACCESS_TOKEN_SERVER + roomName)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
            }

            override fun onResponse(call: Call, response: Response) {
                response.body()?.let {
                    //runOnUiThread({ showToast(it.string()) })
                    val token: JSONObject = JSONObject(it.string())
                    // runOnUiThread({ showToast(token.get("accessToken").toString()) })
                    //println(token.get("accessToken").toString())
                    this@VideoActivity.accessToken = token.get("accessToken").toString()
                    connectToRoom(roomName)
                }
                if (response.body() == null) {
                    runOnUiThread({
                        showToast("Response from server is null")
                    })

                }
            }

        })


    }

    fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun connectToRoom(roomName: String) {

        try {
            audioDeviceSelector.activate();
        } catch (e: Exception) {
        }
        val connectOptionsBuilder = ConnectOptions.Builder(accessToken)
            .roomName(roomName)

        /*
         * Add local audio track to connect options to share with participants.
         */
        localAudioTrack?.let { connectOptionsBuilder.audioTracks(listOf(it)) }

        /*
         * Add local video track to connect options to share with participants.
         */
        localVideoTrack?.let { connectOptionsBuilder.videoTracks(listOf(it)) }

        /*
         * Set the preferred audio and video codec for media.
         */
        connectOptionsBuilder.preferAudioCodecs(listOf(audioCodec))
        connectOptionsBuilder.preferVideoCodecs(listOf(videoCodec))

        /*
         * Set the sender side encoding parameters.
         */
        connectOptionsBuilder.encodingParameters(encodingParameters)

        /*
         * Toggles automatic track subscription. If set to false, the LocalParticipant will receive
         * notifications of track publish events, but will not automatically subscribe to them. If
         * set to true, the LocalParticipant will automatically subscribe to tracks as they are
         * published. If unset, the default is true. Note: This feature is only available for Group
         * Rooms. Toggling the flag in a P2P room does not modify subscription behavior.
         */
        connectOptionsBuilder.enableAutomaticSubscription(enableAutomaticSubscription)

        room = Video.connect(this, connectOptionsBuilder.build(), roomListener)
        try {
            setDisconnectAction()
        } catch (e: Exception) {

        }
    }

    /*
     * The initial state when there is no active room.
     */
    private fun initializeUI() {

        connectActionFab.setImageDrawable(
            ContextCompat.getDrawable(
                this,
                R.drawable.ic_video_call_white_24dp
            )
        )
        connectActionFab.show()
        //connectActionFab.setOnClickListener(connectActionClickListener())
        switchCameraActionFab.hide()
        switchCameraActionFab.setOnClickListener(switchCameraClickListener())
        localVideoActionFab.show()
        localVideoActionFab.setOnClickListener(localVideoClickListener())
        muteActionFab.show()
        muteActionFab.setOnClickListener(muteClickListener())

    }

    /*
     * Show the current available audio devices.
     */
    private fun showAudioDevices() {
        val availableAudioDevices = audioDeviceSelector.availableAudioDevices

        audioDeviceSelector.selectedAudioDevice?.let { selectedDevice ->
            val selectedDeviceIndex = availableAudioDevices.indexOf(selectedDevice)
            val audioDeviceNames = ArrayList<String>()

            for (a in availableAudioDevices) {
                audioDeviceNames.add(a.name)
            }

            AlertDialog.Builder(this)
                .setTitle(R.string.room_screen_select_device)
                .setSingleChoiceItems(
                    audioDeviceNames.toTypedArray<CharSequence>(),
                    selectedDeviceIndex
                ) { dialog, index ->
                    dialog.dismiss()
                    val selectedAudioDevice = availableAudioDevices[index]
                    updateAudioDeviceIcon(selectedAudioDevice)
                    audioDeviceSelector.selectDevice(selectedAudioDevice)
                }.create().show()
        }
    }

    /*
     * Update the menu icon based on the currently selected audio device.
     */
    private fun updateAudioDeviceIcon(selectedAudioDevice: AudioDevice?) {
        val audioDeviceMenuIcon = when (selectedAudioDevice) {
            is BluetoothHeadset -> R.drawable.ic_bluetooth_white_24dp
            is WiredHeadset -> R.drawable.ic_headset_mic_white_24dp
            is Speakerphone -> R.drawable.ic_volume_up_white_24dp
            else -> R.drawable.ic_phonelink_ring_white_24dp
        }

        audioDeviceMenuItem.setIcon(audioDeviceMenuIcon)
    }

    /*
     * The actions performed during disconnect.
     */
    private fun setDisconnectAction() {
        runOnUiThread {

            connectActionFab.setImageDrawable(
                ContextCompat.getDrawable(
                    this,
                    R.drawable.ic_call_end_white_24px
                )
            )
        }

        connectActionFab.show()
        connectActionFab.setOnClickListener(disconnectClickListener())

    }

    /*
     * Creates an connect UI dialog
     */
//    private fun showConnectDialog() {
//        val roomEditText = EditText(this)
//        alertDialog = createConnectDialog(
//            roomEditText,
//            connectClickListener(roomEditText), cancelConnectDialogClickListener(), this
//        )
//        alertDialog!!.show()
//    }

    /*
     * Called when participant joins the room
     */
    private fun addRemoteParticipant(remoteParticipant: RemoteParticipant) {
        /*
         * This app only displays video for one additional participant per Room
         */
        if (thumbnailVideoView.visibility == View.VISIBLE) {
            Snackbar.make(
                connectActionFab,
                "Multiple participants are not currently support in this UI",
                Snackbar.LENGTH_LONG
            )
                .setAction("Action", null).show()
            return
        }
        participantIdentity = remoteParticipant.identity
        videoStatusTextView.text = "Participant $participantIdentity joined"

        /*
         * Add participant renderer
         */
        remoteParticipant.remoteVideoTracks.firstOrNull()?.let { remoteVideoTrackPublication ->
            if (remoteVideoTrackPublication.isTrackSubscribed) {
                remoteVideoTrackPublication.remoteVideoTrack?.let { addRemoteParticipantVideo(it) }
            }
        }

        /*
         * Start listening for participant events
         */
        remoteParticipant.setListener(participantListener)
    }

    /*
     * Set primary view as renderer for participant video track
     */
    private fun addRemoteParticipantVideo(videoTrack: VideoTrack) {
        moveLocalVideoToThumbnailView()
        primaryVideoView.mirror = false
        videoTrack.addRenderer(primaryVideoView)
    }

    private fun moveLocalVideoToThumbnailView() {
        if (thumbnailVideoView.visibility == View.GONE) {
            thumbnailVideoView.visibility = View.VISIBLE
            with(localVideoTrack) {
                this?.removeRenderer(primaryVideoView)
                this?.addRenderer(thumbnailVideoView)
            }
            localVideoView = thumbnailVideoView
            thumbnailVideoView.mirror = cameraCapturerCompat.cameraSource ==
                    CameraCapturer.CameraSource.FRONT_CAMERA
        }
    }

    /*
     * Called when participant leaves the room
     */
    private fun removeRemoteParticipant(remoteParticipant: RemoteParticipant) {
        videoStatusTextView.text = "Participant $remoteParticipant.identity left."
        if (remoteParticipant.identity != participantIdentity) {
            return
        }

        /*
         * Remove participant renderer
         */
        remoteParticipant.remoteVideoTracks.firstOrNull()?.let { remoteVideoTrackPublication ->
            if (remoteVideoTrackPublication.isTrackSubscribed) {
                remoteVideoTrackPublication.remoteVideoTrack?.let { removeParticipantVideo(it) }
            }
        }
        moveLocalVideoToPrimaryView()
    }

    private fun removeParticipantVideo(videoTrack: VideoTrack) {
        videoTrack.removeRenderer(primaryVideoView)
    }

    private fun moveLocalVideoToPrimaryView() {
        if (thumbnailVideoView.visibility == View.VISIBLE) {
            thumbnailVideoView.visibility = View.GONE
            with(localVideoTrack) {
                this?.removeRenderer(thumbnailVideoView)
                this?.addRenderer(primaryVideoView)
            }
            localVideoView = primaryVideoView
            primaryVideoView.mirror = cameraCapturerCompat.cameraSource ==
                    CameraCapturer.CameraSource.FRONT_CAMERA
        }
    }

    private fun connectClickListener(roomEditText: EditText): DialogInterface.OnClickListener {
        return DialogInterface.OnClickListener { _, _ ->
            /*
             * Connect to room
             */
            retrieveAccessTokenfromServer(roomEditText.text.toString())

        }
    }

    private fun disconnectClickListener(): View.OnClickListener {
        return View.OnClickListener {
            /*
             * Disconnect from room
             */
            room?.disconnect()

            onBackPressed()
            initializeUI()
        }
    }


//    private fun connectActionClickListener(): View.OnClickListener {
//        return View.OnClickListener { showConnectDialog() }
//    }
//
//    private fun cancelConnectDialogClickListener(): DialogInterface.OnClickListener {
//        return DialogInterface.OnClickListener { _, _ ->
//            initializeUI()
//            alertDialog!!.dismiss()
//        }
//    }

    private fun switchCameraClickListener(): View.OnClickListener {
        return View.OnClickListener {
            val cameraSource = cameraCapturerCompat.cameraSource
            cameraCapturerCompat.switchCamera()
            if (thumbnailVideoView.visibility == View.VISIBLE) {
                thumbnailVideoView.mirror = cameraSource == CameraCapturer.CameraSource.BACK_CAMERA
            } else {
                primaryVideoView.mirror = cameraSource == CameraCapturer.CameraSource.BACK_CAMERA
            }
        }
    }

    private fun localVideoClickListener(): View.OnClickListener {
        return View.OnClickListener {
            /*
             * Enable/disable the local video track
             */
            localVideoTrack?.let {
                val enable = !it.isEnabled
                it.enable(enable)
                val icon: Int
                if (enable) {
                    icon = R.drawable.ic_videocam_white_24dp
                    switchCameraActionFab.show()
                } else {
                    icon = R.drawable.ic_videocam_off_black_24dp
                    switchCameraActionFab.hide()
                }
                localVideoActionFab.setImageDrawable(
                    ContextCompat.getDrawable(this@VideoActivity, icon)
                )
            }
        }
    }

    private fun muteClickListener(): View.OnClickListener {
        return View.OnClickListener {
            /*
             * Enable/disable the local audio track. The results of this operation are
             * signaled to other Participants in the same Room. When an audio track is
             * disabled, the audio is muted.
             */
            localAudioTrack?.let {
                val enable = !it.isEnabled
                it.enable(enable)
                val icon = if (enable)
                    R.drawable.ic_mic_white_24dp
                else
                    R.drawable.ic_mic_off_black_24dp
                muteActionFab.setImageDrawable(
                    ContextCompat.getDrawable(
                        this@VideoActivity, icon
                    )
                )
            }
        }
    }

//    private fun retrieveAccessTokenfromServer() {
//        Ion.with(this)
//                .load("$ACCESS_TOKEN_SERVER?identity=${UUID.randomUUID()}")
//                .asString()
//                .setCallback { e, token ->
//                    if (e == null) {
//                        this@VideoActivity.accessToken = token
//                    } else {
//                        Toast.makeText(this@VideoActivity,
//                                R.string.error_retrieving_access_token, Toast.LENGTH_LONG)
//                                .show()
//                    }
//                }
//    }

    private fun createConnectDialog(
        participantEditText: EditText,
        callParticipantsClickListener: DialogInterface.OnClickListener,
        cancelClickListener: DialogInterface.OnClickListener,
        context: Context
    ): AlertDialog {
        val alertDialogBuilder = AlertDialog.Builder(context).apply {
            setIcon(R.drawable.ic_video_call_white_24dp)
            setTitle("Connect to a room")
            setPositiveButton("Connect", callParticipantsClickListener)
            setNegativeButton("Cancel", cancelClickListener)
            setCancelable(false)
        }

        setRoomNameFieldInDialog(participantEditText, alertDialogBuilder, context)

        return alertDialogBuilder.create()
    }

    @SuppressLint("RestrictedApi")
    private fun setRoomNameFieldInDialog(
        roomNameEditText: EditText,
        alertDialogBuilder: AlertDialog.Builder,
        context: Context
    ) {
        roomNameEditText.hint = "room name"
        val horizontalPadding =
            context.resources.getDimensionPixelOffset(R.dimen.activity_horizontal_margin)
        val verticalPadding =
            context.resources.getDimensionPixelOffset(R.dimen.activity_vertical_margin)
        alertDialogBuilder.setView(
            roomNameEditText,
            horizontalPadding,
            verticalPadding,
            horizontalPadding,
            0
        )
    }
}
