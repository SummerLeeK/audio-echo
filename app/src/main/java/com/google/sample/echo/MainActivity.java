/*
 * Copyright 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.sample.echo;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v7.widget.AppCompatSeekBar;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

public class MainActivity extends Activity
        implements ActivityCompat.OnRequestPermissionsResultCallback {
    private static final int AUDIO_ECHO_REQUEST = 0;
    Button controlButton;
    Button play_control_button;
    TextView statusView;
    String nativeSampleRate;
    String nativeSampleBufSize;
    boolean supportRecording;

    AppCompatSeekBar volume;
    Boolean isPlaying = false;
    boolean isStop = false;
    boolean isPause = false;
    private boolean isMute = false;


    private Handler handler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            statusView.setText(getAudioPlayerVolumeLevel() + "\tmax:" + getMaxAudioPlayerVolumeLevel());
            handler.sendEmptyMessageDelayed(0, 10);
        }
    };
    private String path = Environment.getExternalStorageDirectory() + File.separator + "ccca.pcm";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        controlButton = (Button) findViewById((R.id.capture_control_button));
        play_control_button = (Button) findViewById((R.id.play_control_button));
        volume = (AppCompatSeekBar) findViewById(R.id.volume);

        volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {

                if (fromUser) {
                    setAudioPlayerVolumeLevel(progress - 10000);
                    handler.sendEmptyMessage(0);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {

            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {

            }
        });

        statusView = (TextView) findViewById(R.id.statusView);
        queryNativeAudioParameters();

        // initialize native audio system
        updateNativeAudioUI();
        if (supportRecording) {
            createSLEngine(Integer.parseInt(nativeSampleRate), Integer.parseInt(nativeSampleBufSize));
            if (!createSLBufferQueueAudioPlayer()) {
                statusView.setText(getString(R.string.error_player));
            }

            if (!createAudioRecorder(path)) {
                deleteSLBufferQueueAudioPlayer();
                statusView.setText(getString(R.string.error_recorder));
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (supportRecording) {
            if (isPlaying) {
                stopPlay();
            }
            deleteSLEngine();
            isPlaying = false;
        }
        super.onDestroy();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private void startEcho() {
        if (!supportRecording) {
            return;
        }
        if (!isPlaying) {
            if (!isPause) {
                startRecord();

            } else {
                restartRecord();
                setAudioPlayNotMute();
            }
            statusView.setText(getString(R.string.status_echoing));
        } else {
            pauseRecord();  //this must include stopRecording()
            setAudioPlayMute();
            updateNativeAudioUI();
            isPause = true;
//            deleteAudioRecorder();
//            deleteSLBufferQueueAudioPlayer();
        }
        isPlaying = !isPlaying;
        controlButton.setText(getString((isPlaying == true) ?
                R.string.StopEcho : R.string.StartEcho));
    }


    public void onEchoClick(View view) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED) {
            statusView.setText(getString(R.string.status_record_perm));
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    AUDIO_ECHO_REQUEST);
            return;
        }
        startEcho();
    }


    public void onPlayClick(View view) {

        if (isMute) {
            if (setAudioPlayNotMute() == 0) {
                isMute = false;
                play_control_button.setText("NotMute");
            }

        } else {
            if (setAudioPlayMute() == 0) {
                isMute = true;
                play_control_button.setText("Mute");
            }
//            setAudioPlayMute();

        }
    }

    public void getLowLatencyParameters(View view) {
        updateNativeAudioUI();
        return;
    }

    private void queryNativeAudioParameters() {
        AudioManager myAudioMgr = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        nativeSampleRate = myAudioMgr.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE);
//        nativeSampleRate = "8000";
        nativeSampleBufSize = myAudioMgr.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER);
        int recBufSize = AudioRecord.getMinBufferSize(
                Integer.parseInt(nativeSampleRate),
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        supportRecording = true;
        if (recBufSize == AudioRecord.ERROR ||
                recBufSize == AudioRecord.ERROR_BAD_VALUE) {
            supportRecording = false;
        }
    }

    private void updateNativeAudioUI() {
        if (!supportRecording) {
            statusView.setText(getString(R.string.error_no_mic));
            controlButton.setEnabled(false);
            return;
        }

        statusView.setText("nativeSampleRate    = " + nativeSampleRate + "\n" +
                "nativeSampleBufSize = " + nativeSampleBufSize + "\n");

    }

    @Override
    protected void onStop() {
        super.onStop();

        Log.e("DEBUG", "onStop");
        if (isPlaying) {
            pauseRecord();  //this must include stopRecording()
            setAudioPlayMute();
            updateNativeAudioUI();
            isStop = true;
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.e("DEBUG", "onPause");
    }

    @Override
    protected void onResume() {
        Log.e("DEBUG", "onResume");
        super.onResume();
        if (isStop) {
//            startRecord();
            restartRecord();
            setAudioPlayNotMute();
            statusView.setText(getString(R.string.status_echoing));
            isStop = false;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        /*
         * if any permission failed, the sample could not play
         */
        if (AUDIO_ECHO_REQUEST != requestCode) {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
            return;
        }

        if (grantResults.length != 1 ||
                grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            /*
             * When user denied permission, throw a Toast to prompt that RECORD_AUDIO
             * is necessary; also display the status on UI
             * Then application goes back to the original state: it behaves as if the button
             * was not clicked. The assumption is that user will re-click the "start" button
             * (to retry), or shutdown the app in normal way.
             */
            statusView.setText(getString(R.string.error_no_permission));
            Toast.makeText(getApplicationContext(),
                    getString(R.string.prompt_permission),
                    Toast.LENGTH_SHORT).show();
            return;
        }

        /*
         * When permissions are granted, we prompt the user the status. User would
         * re-try the "start" button to perform the normal operation. This saves us the extra
         * logic in code for async processing of the button listener.
         */
        statusView.setText("RECORD_AUDIO permission granted, touch " +
                getString(R.string.StartEcho) + " to begin");


        // The callback runs on app's thread, so we are safe to resume the action
        startEcho();
    }

    /*
     * Loading our Libs
     */
    static {
        System.loadLibrary("echo");
    }

    /*
     * jni function implementations...
     */
    public static native void createSLEngine(int rate, int framesPerBuf);

    public static native void deleteSLEngine();

    public static native boolean createSLBufferQueueAudioPlayer();

    public static native void deleteSLBufferQueueAudioPlayer();

    public static native boolean createAudioRecorder(String path);

    public static native void deleteAudioRecorder();

    public static native void startRecord();

    public static native void pauseRecord();

    public static native void restartRecord();

    public static native void stopRecord();

    public static native void startPlay();

    public static native void stopPlay();

    public static native int setAudioPlayMute();

    public static native int setAudioPlayNotMute();

    public static native short getAudioRecorderVolumeLevel();

    public static native short getAudioPlayerVolumeLevel();

    public static native short getMaxAudioPlayerVolumeLevel();

    public static native int setAudioPlayerVolumeLevel(int volume);
}
