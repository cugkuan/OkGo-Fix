/*
 * Copyright 2016 jeasonlzy(廖子尧)
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
package com.lzy.okserver.download;

import android.content.ContentValues;
import android.text.TextUtils;
import android.util.ArrayMap;

import com.lzy.okgo.db.DownloadManager;
import com.lzy.okgo.exception.HttpException;
import com.lzy.okgo.exception.OkGoException;
import com.lzy.okgo.exception.StorageException;
import com.lzy.okgo.model.HttpHeaders;
import com.lzy.okgo.model.Progress;
import com.lzy.okgo.request.base.Request;
import com.lzy.okgo.utils.HttpUtils;
import com.lzy.okgo.utils.IOUtils;
import com.lzy.okgo.utils.OkLogger;
import com.lzy.okserver.OkDownload;
import com.lzy.okserver.task.PriorityRunnable;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;

import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * ================================================
 * 作    者：jeasonlzy（廖子尧）Github地址：https://github.com/jeasonlzy
 * 版    本：1.0
 * 创建日期：2016/1/19
 * 描    述：文件的下载任务类
 * 修订历史：
 * ================================================
 * <p>
 * 2021 -5-12
 * 在断点续传中，关于范围的请求和
 * 返回的 code 200 和 206 的区别
 * 修复 断点续传中，文件更改的bug.
 * 使用 progress 的eTag 作为文件是否更改的标志位
 */
public class DownloadTask implements Runnable {

    private static final int BUFFER_SIZE = 1024 * 8;

    public Progress progress;
    public Map<Object, DownloadListener> listeners;
    private ThreadPoolExecutor executor;
    private PriorityRunnable priorityRunnable;

    public DownloadTask(String tag, Request<File, ? extends Request> request) {
        HttpUtils.checkNotNull(tag, "tag == null");
        progress = new Progress();
        progress.tag = tag;
        progress.folder = OkDownload.getInstance().getFolder();
        progress.url = request.getBaseUrl();
        progress.status = Progress.NONE;
        progress.totalSize = -1;
        progress.request = request;

        executor = OkDownload.getInstance().getThreadPool().getExecutor();
        listeners = new HashMap<>();
    }

    public DownloadTask(Progress progress) {
        HttpUtils.checkNotNull(progress, "progress == null");
        this.progress = progress;
        executor = OkDownload.getInstance().getThreadPool().getExecutor();
        listeners = new ArrayMap<>();
    }

    public DownloadTask folder(String folder) {
        if (folder != null && !TextUtils.isEmpty(folder.trim())) {
            progress.folder = folder;
        } else {
            OkLogger.w("folder is null, ignored!");
        }
        return this;
    }

    public DownloadTask fileName(String fileName) {
        if (fileName != null && !TextUtils.isEmpty(fileName.trim())) {
            progress.fileName = fileName;
        } else {
            OkLogger.w("fileName is null, ignored!");
        }
        return this;
    }

    public DownloadTask tempFileName(String tempFileName) {
        if (tempFileName != null && !TextUtils.isEmpty(tempFileName.trim())) {
            progress.tempFileName = tempFileName;
        } else {
            OkLogger.w("tempFileName is null, ignored!");
        }
        return this;
    }

    public DownloadTask priority(int priority) {
        progress.priority = priority;
        return this;
    }

    public DownloadTask extra1(Serializable extra1) {
        progress.extra1 = extra1;
        return this;
    }

    public DownloadTask extra2(Serializable extra2) {
        progress.extra2 = extra2;
        return this;
    }

    public DownloadTask extra3(Serializable extra3) {
        progress.extra3 = extra3;
        return this;
    }

    public DownloadTask fileSuffix(String fileSuffix) {
        progress.fileSuffix = fileSuffix;
        return this;
    }

    public DownloadTask save() {
        if (!TextUtils.isEmpty(progress.folder) && !TextUtils.isEmpty(progress.fileName)) {
            progress.filePath = new File(progress.folder, progress.fileName).getAbsolutePath();
        }
        DownloadManager.getInstance().replace(progress);
        return this;
    }

    public DownloadTask register(DownloadListener listener) {
        if (listener != null) {
            HttpUtils.runOnUiThread(() -> {
                listeners.put(listener.tag, listener);
            });
        }
        return this;
    }

    public void unRegister(DownloadListener listener) {
        HttpUtils.checkNotNull(listener, "listener == null");
        HttpUtils.runOnUiThread(() -> {
            listeners.remove(listener.tag);
        });
    }

    public void unRegister(String tag) {
        HttpUtils.checkNotNull(tag, "tag == null");
        HttpUtils.runOnUiThread(() -> {
            listeners.remove(tag);
        });
    }

    public void start() {
        if (OkDownload.getInstance().getTask(progress.tag) == null || DownloadManager.getInstance().get(progress.tag) == null) {
            throw new IllegalStateException("you must call DownloadTask#save() before DownloadTask#start()！");
        }
        if (progress.status == Progress.NONE || progress.status == Progress.PAUSE || progress.status == Progress.ERROR) {
            postOnStart(progress);
            postWaiting(progress);
            priorityRunnable = new PriorityRunnable(progress.priority, this);
            executor.execute(priorityRunnable);
        } else if (progress.status == Progress.FINISH) {
            if (progress.filePath == null) {
                postOnError(progress, new StorageException("the file of the task with tag:" + progress.tag + " may be invalid or damaged, please call the method restart() to download again！"));
            } else {
                File file = new File(progress.filePath);
                if (!file.exists() && !TextUtils.isEmpty(progress.fileSuffix)) {
                    file = new File(file.getParent(), progress.fileName + progress.fileSuffix);
                }
                if (file.exists() && file.length() == progress.totalSize) {
                    postOnFinish(progress, new File(progress.filePath));
                } else {
                    postOnError(progress, new StorageException("the file " + progress.filePath + " may be invalid or damaged, please call the method restart() to download again！"));
                }
            }
        } else {
            OkLogger.w("the task with tag " + progress.tag + " is already in the download queue, current task status is " + progress.status);
        }
    }

    public void restart() {
        pause();
        // 注意，临时文件也要删除
        IOUtils.delFileOrFolder(progress.filePath);
        IOUtils.delFileOrFolder(new File(progress.folder, progress.tempFileName).getAbsoluteFile());
        progress.status = Progress.NONE;
        progress.currentSize = 0;
        progress.fraction = 0;
        progress.speed = 0;
        DownloadManager.getInstance().replace(progress);
        start();
    }

    /**
     * 暂停的方法
     */
    public void pause() {
        executor.remove(priorityRunnable);
        if (progress.status == Progress.WAITING) {
            postPause(progress);
        } else if (progress.status == Progress.LOADING) {
            progress.speed = 0;
            progress.status = Progress.PAUSE;
        } else {
            OkLogger.w("only the task with status WAITING(1) or LOADING(2) can pause, current status is " + progress.status);
        }
    }

    /**
     * 删除一个任务,会删除下载文件
     */
    public void remove() {
        remove(false);
    }

    /**
     * 删除一个任务,会删除下载文件
     */
    public DownloadTask remove(boolean isDeleteFile) {
        pause();
        if (isDeleteFile) {
            IOUtils.delFileOrFolder(progress.filePath);
            IOUtils.delFileOrFolder(new File(progress.folder, progress.tempFileName).getAbsoluteFile());
        }
        DownloadManager.getInstance().delete(progress.tag);
        DownloadTask task = OkDownload.getInstance().removeTask(progress.tag);
        postOnRemove(progress);
        return task;
    }


    @Override
    public void run() {
        //check breakpoint
        long startPosition = progress.currentSize;
        if (startPosition < 0) {
            progress.speed = 0;
            progress.status = Progress.NONE;
            progress.currentSize = 0;
            startPosition = 0;
            updateDatabase(progress);
        }
        if (startPosition > 0) {
            if (!TextUtils.isEmpty(progress.filePath)) {
                File file;
                if (!TextUtils.isEmpty(progress.tempFileName)) {
                    file = new File(progress.folder, progress.tempFileName);
                } else {
                    file = new File(progress.filePath);
                }
                if (!file.exists()) {
                    progress.speed = 0;
                    progress.status = Progress.NONE;
                    progress.currentSize = 0;
                    startPosition = 0;
                    updateDatabase(progress);
                }
            }
        }
        //request network from startPosition
        Response response;
        try {
            Request<?, ? extends Request> request = progress.request;
            //断点续传的条件设置
            if (progress.extra1 != null && startPosition > 0) {
                request.headers(HttpHeaders.HEAD_KEY_IF_RANGE, progress.extra1.toString());
                request.headers(HttpHeaders.HEAD_KEY_RANGE, "bytes=" + startPosition + "-");
            }
            response = request.execute();
        } catch (IllegalArgumentException | IOException e) {
            // 新增 对 非 https 和 http 协议地址，抛出IllegalArgumentException
            postOnError(progress, e);
            return;
        }
        //check network data
        int code = response.code();
        if (code == 404 || code >= 500) {
            postOnError(progress, HttpException.NET_ERROR());
            return;
        }
        if (code == 416) {
            //表示请求的文件范围不合法，暂时处理为重新下载整个文件
            progress.extra1 = null;
            progress.fraction = 0;
            progress.currentSize = 0;
            postOnError(progress, HttpException.COMMON("文件过期，需要重新下载"));
            return;
        }
        //文件修改的标志位，优先使用 etag
        String extra = response.header(HttpHeaders.HEAD_KEY_E_TAG);
        if (TextUtils.isEmpty(extra)) {
            extra = response.header(HttpHeaders.HEAD_KEY_LAST_MODIFIED);
        }
        progress.extra1 = extra;
        //200 表示这是一个新的文件下载,206 表示断点续传
        if (code == 200) {
            progress.currentSize = 0;
            progress.fraction = 0f;
            startPosition = 0;
        }
        updateDatabase(progress);

        ResponseBody body = response.body();
        if (body == null) {
            postOnError(progress, new HttpException("response body is null"));
            return;
        }
        if (progress.totalSize == -1) {
            progress.totalSize = body.contentLength();
        }
        // 针对服务器 body.contentLength = -1 的情况定制
        if (progress.totalSize == -1) {
            progress.totalSize = Long.MAX_VALUE;
        }
        //create filename
        String fileName = progress.fileName;
        if (TextUtils.isEmpty(fileName)) {
            fileName = HttpUtils.getNetFileName(response, progress.url);
            progress.fileName = fileName;
        }
        if (!IOUtils.createFolder(progress.folder)) {
            postOnError(progress, StorageException.NOT_AVAILABLE());
            return;
        }

        //create and check file

        if (TextUtils.isEmpty(progress.filePath)) {
            File file = new File(progress.folder, progress.fileName);
            progress.filePath = file.getAbsolutePath();
        }

        File downloadFile;
        // 如果有临时文件，那么，用临时文件作为下载文件
        if (!TextUtils.isEmpty(progress.tempFileName)) {
            downloadFile = new File(progress.folder, progress.tempFileName);
        } else {
            downloadFile = new File(progress.filePath);
        }
        if (startPosition > 0 && !downloadFile.exists()) {
            postOnError(progress, OkGoException.BREAKPOINT_EXPIRED());
            return;
        }
        if (startPosition > progress.totalSize) {
            postOnError(progress, OkGoException.BREAKPOINT_EXPIRED());
            return;
        }
        if (startPosition == 0 && downloadFile.exists()) {
            IOUtils.delFileOrFolder(downloadFile);
        }
        if (startPosition == progress.totalSize && startPosition > 0) {
            if (downloadFile.exists() && startPosition == downloadFile.length()) {
                // 下载完成，改名字
                if (!TextUtils.isEmpty(progress.tempFileName)) {
                    File disFile = new File(progress.filePath);
                    downloadFile.renameTo(disFile);
                    postOnFinish(progress, disFile);
                } else {
                    postOnFinish(progress, downloadFile);
                }
            } else {
                postOnError(progress, OkGoException.BREAKPOINT_EXPIRED());
            }
            return;
        }
        //start downloading
        RandomAccessFile randomAccessFile;
        try {
            randomAccessFile = new RandomAccessFile(downloadFile, "rw");
            randomAccessFile.seek(startPosition);
            progress.currentSize = startPosition;
        } catch (Exception e) {
            postOnError(progress, e);
            return;
        }
        DownloadManager.getInstance().replace(progress);
        try {
            download(body.byteStream(), randomAccessFile, progress);
        } catch (IOException e) {
            postOnError(progress, e);
            return;
        }
        //check finish status
        if (progress.status == Progress.PAUSE) {
            postPause(progress);
        } else if (progress.status == Progress.LOADING) {
            if (downloadFile.length() == progress.totalSize) {
                // 下载完成，更改名字
                if (!TextUtils.isEmpty(progress.tempFileName)) {
                    File disFile = new File(progress.filePath);
                    downloadFile.renameTo(disFile);
                    postOnFinish(progress, disFile);
                } else {
                    postOnFinish(progress, downloadFile);
                }
            } else {
                postOnError(progress, OkGoException.BREAKPOINT_EXPIRED());
            }
        } else {
            postOnError(progress, OkGoException.UNKNOWN());
        }
    }

    /**
     * 执行文件下载
     */
    private void download(InputStream input, RandomAccessFile out, Progress progress) throws IOException {
        if (input == null || out == null) return;
        progress.status = Progress.LOADING;
        byte[] buffer = new byte[BUFFER_SIZE];
        BufferedInputStream in = new BufferedInputStream(input, BUFFER_SIZE);
        int len;
        try {
            while ((len = in.read(buffer, 0, BUFFER_SIZE)) != -1 && progress.status == Progress.LOADING) {
                out.write(buffer, 0, len);
                Progress.changeProgress(progress, len, progress.totalSize, this::postLoading);
            }
            if (progress.totalSize == Long.MAX_VALUE) {
                progress.totalSize = progress.currentSize;
            }
        } finally {
            IOUtils.closeQuietly(out);
            IOUtils.closeQuietly(in);
            IOUtils.closeQuietly(input);
        }
    }

    private void postOnStart(final Progress progress) {
        progress.speed = 0;
        progress.status = Progress.NONE;
        updateDatabase(progress);
        HttpUtils.runOnUiThread(() -> {
            for (DownloadListener listener : listeners.values()) {
                listener.onStart(progress);
            }
        });
    }

    private void postWaiting(final Progress progress) {
        progress.speed = 0;
        progress.status = Progress.WAITING;
        updateDatabase(progress);
        HttpUtils.runOnUiThread(() -> {
            for (DownloadListener listener : listeners.values()) {
                listener.onProgress(progress);
            }
        });
    }

    private void postPause(final Progress progress) {
        progress.speed = 0;
        progress.status = Progress.PAUSE;
        updateDatabase(progress);
        HttpUtils.runOnUiThread(() -> {
            for (DownloadListener listener : listeners.values()) {
                listener.onProgress(progress);
            }
        });
    }

    private void postLoading(final Progress progress) {
        updateDatabase(progress);
        HttpUtils.runOnUiThread(() -> {
            for (DownloadListener listener : listeners.values()) {
                listener.onProgress(progress);
            }
        });
    }

    private void postOnError(final Progress progress, final Throwable throwable) {
        progress.speed = 0;
        progress.status = Progress.ERROR;
        progress.exception = throwable;
        updateDatabase(progress);
        HttpUtils.runOnUiThread(() -> {
            for (DownloadListener listener : listeners.values()) {
                listener.onProgress(progress);
                listener.onError(progress);
            }
        });
    }

    private void postOnFinish(final Progress progress, final File file) {
        progress.speed = 0;
        progress.fraction = 1.0f;
        progress.status = Progress.FINISH;
        updateDatabase(progress);
        HttpUtils.runOnUiThread(() -> {
            for (DownloadListener listener : listeners.values()) {
                listener.onProgress(progress);
                listener.onFinish(file, progress);
            }
        });
    }

    private void postOnRemove(final Progress progress) {
        updateDatabase(progress);
        HttpUtils.runOnUiThread(() -> {
            for (DownloadListener listener : listeners.values()) {
                listener.onRemove(progress);
            }
            listeners.clear();
        });
    }

    private void updateDatabase(Progress progress) {
        ContentValues contentValues = Progress.buildUpdateContentValues(progress);
        DownloadManager.getInstance().update(contentValues, progress.tag);
    }
}
