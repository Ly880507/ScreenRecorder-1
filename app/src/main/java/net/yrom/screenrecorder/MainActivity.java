/*
 * Copyright (c) 2014 Yrom Wang <http://www.yrom.net>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.yrom.screenrecorder;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import net.yrom.screenrecorder.model.DanmakuBean;
import net.yrom.screenrecorder.rtmp.RESFlvData;
import net.yrom.screenrecorder.rtmp.RESFlvDataCollecter;
import net.yrom.screenrecorder.service.ScreenRecordListenerService;
import net.yrom.screenrecorder.task.RtmpStreamingSender;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements View.OnClickListener {
    private static final int REQUEST_CODE = 1;
    private Button mButton;
    private EditText mRtmpAddET;
    private MediaProjectionManager mMediaProjectionManager;
    private ScreenRecorder mRecorder;
    private RtmpStreamingSender streamingSender;
    private ExecutorService executorService;
    private List<DanmakuBean> danmakuBeanList = new ArrayList<>();
    private String rtmpAddr;
    private boolean isRecording;

    private IScreenRecorderAidlInterface recorderAidlInterface;
    private ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            recorderAidlInterface = IScreenRecorderAidlInterface.Stub.asInterface(service);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            recorderAidlInterface = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mButton = (Button) findViewById(R.id.button);
        mRtmpAddET = (EditText) findViewById(R.id.et_rtmp_address);
        mButton.setOnClickListener(this);
        mMediaProjectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        MediaProjection mediaProjection = mMediaProjectionManager.getMediaProjection(resultCode, data);
        if (mediaProjection == null) {
            Log.e("@@", "media projection is null");
            return;
        }
        rtmpAddr = mRtmpAddET.getText().toString().trim();
        if (TextUtils.isEmpty(rtmpAddr)) {
            Toast.makeText(this, "rtmp address cannot be null", Toast.LENGTH_SHORT).show();
            return;
        }
        streamingSender = new RtmpStreamingSender();
        streamingSender.sendStart(rtmpAddr);
        RESFlvDataCollecter collecter = new RESFlvDataCollecter() {
            @Override
            public void collect(RESFlvData flvData, int type) {
                streamingSender.sendFood(flvData, type);
            }
        };
        mRecorder = new ScreenRecorder(collecter, RESFlvData.VIDEO_WIDTH, RESFlvData.VIDEO_HEIGHT, RESFlvData.VIDEO_BITRATE, 1, mediaProjection);
        mRecorder.start();
        executorService = Executors.newCachedThreadPool();
        executorService.execute(streamingSender);
        mButton.setText("Stop Recorder");
        Toast.makeText(this, "Screen recorder is running...", Toast.LENGTH_SHORT).show();
        moveTaskToBack(true);
    }

    @Override
    public void onClick(View v) {
        if (mRecorder != null) {
            mRecorder.quit();
            mRecorder = null;
            if (streamingSender != null) {
                streamingSender.sendStop();
                streamingSender.quit();
                streamingSender = null;
            }
            if (executorService != null) {
                executorService.shutdown();
                executorService = null;
            }
            mButton.setText("Restart recorder");
        } else {
            isRecording = true;
            Intent captureIntent = mMediaProjectionManager.createScreenCaptureIntent();
            startActivityForResult(captureIntent, REQUEST_CODE);
        }
    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mRecorder != null) {
            mRecorder.quit();
            mRecorder = null;
            if (streamingSender != null) {
                streamingSender.quit();
                streamingSender = null;
            }
            if (executorService != null) {
                executorService.shutdown();
                executorService = null;
            }
            mButton.setText("Restart recorder");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isRecording) stopScreenRecordService();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (isRecording) startScreenRecordService();
    }

    private void startScreenRecordService() {
        if (mRecorder != null && mRecorder.getStatus()) {
            Intent runningServiceIT = new Intent(this, ScreenRecordListenerService.class);
            bindService(runningServiceIT, connection, BIND_AUTO_CREATE);
            startService(runningServiceIT);
            startAutoSendDanmaku();
        }
    }

    private void startAutoSendDanmaku() {
        ExecutorService exec = Executors.newCachedThreadPool();
        exec.execute(new Runnable() {
            @Override
            public void run() {
                int index = 0;
                while (true) {
                    DanmakuBean danmakuBean = new DanmakuBean();
                    danmakuBean.setMessage(String.valueOf(index++));
                    danmakuBean.setName("little girl");
                    danmakuBeanList.add(danmakuBean);
                    try {
                        if (recorderAidlInterface != null) {
                            recorderAidlInterface.sendDanmaku(danmakuBeanList);
                        }
                    } catch (RemoteException e) {
                        e.printStackTrace();
                    }
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        });
    }

    private void stopScreenRecordService() {
        Intent runningServiceIT = new Intent(this, ScreenRecordListenerService.class);
        stopService(runningServiceIT);
        if (mRecorder != null && mRecorder.getStatus()) {
            Toast.makeText(this, "现在正在进行录屏直播哦", Toast.LENGTH_SHORT).show();
        }
    }

}
