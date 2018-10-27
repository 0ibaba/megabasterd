package com.tonikelope.megabasterd;

import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;
import static com.tonikelope.megabasterd.MiscTools.*;
import static com.tonikelope.megabasterd.MainPanel.*;
import java.io.File;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;

/**
 *
 * @author tonikelope
 */
public class ChunkDownloader implements Runnable, SecureSingleThreadNotifiable {

    public static final double SLOW_PROXY_PERC = 0.5;
    private final int _id;
    private final Download _download;
    private volatile boolean _exit;
    private final Object _secure_notify_lock;
    private volatile boolean _error_wait;
    private boolean _notified;
    private SmartMegaProxyManager _proxy_manager;

    public ChunkDownloader(int id, Download download) {
        _notified = false;
        _exit = false;
        _secure_notify_lock = new Object();
        _id = id;
        _download = download;
        _proxy_manager = null;
        _error_wait = false;

    }

    public void setExit(boolean exit) {
        _exit = exit;
    }

    public boolean isExit() {
        return _exit;
    }

    public Download getDownload() {
        return _download;
    }

    public int getId() {
        return _id;
    }

    public boolean isError_wait() {
        return _error_wait;
    }

    public void setError_wait(boolean error_wait) {
        _error_wait = error_wait;
    }

    @Override
    public void secureNotify() {
        synchronized (_secure_notify_lock) {

            _notified = true;

            _secure_notify_lock.notify();
        }
    }

    @Override
    public void secureWait() {

        synchronized (_secure_notify_lock) {
            while (!_notified) {

                try {
                    _secure_notify_lock.wait();
                } catch (InterruptedException ex) {
                    _exit = true;
                    Logger.getLogger(getClass().getName()).log(Level.SEVERE, null, ex);
                }
            }

            _notified = false;
        }
    }

    @Override
    public void run() {

        Logger.getLogger(getClass().getName()).log(Level.INFO, "{0} Worker [{1}]: let''s do some work!", new Object[]{Thread.currentThread().getName(), _id});

        HttpURLConnection con;

        try {

            int http_error = 0, conta_error = 0;

            boolean timeout, chunk_error = false, slow_proxy = false;

            String worker_url = null, current_smart_proxy = null;

            long init_chunk_time = -1, finish_chunk_time = -1, pause_init_time = -1, paused = 0L;

            while (!_exit && !_download.isStopped()) {

                if (worker_url == null || http_error == 403) {

                    worker_url = _download.getDownloadUrlForWorker();
                }

                long chunk_id = _download.nextChunkId();

                long chunk_offset = ChunkManager.calculateChunkOffset(chunk_id, Download.CHUNK_SIZE_MULTI);

                long chunk_size = ChunkManager.calculateChunkSize(chunk_id, _download.getFile_size(), chunk_offset, Download.CHUNK_SIZE_MULTI);

                ChunkManager.checkChunkID(chunk_id, _download.getFile_size(), chunk_offset);

                String chunk_url = ChunkManager.genChunkUrl(worker_url, _download.getFile_size(), chunk_offset, chunk_size);

                if ((http_error == 509 || slow_proxy) && MainPanel.isUse_smart_proxy() && !MainPanel.isUse_proxy()) {

                    if (_proxy_manager == null) {

                        _proxy_manager = new SmartMegaProxyManager(null);

                    }

                    if (current_smart_proxy != null) {

                        _proxy_manager.blockProxy(current_smart_proxy);

                        Logger.getLogger(MiscTools.class.getName()).log(Level.WARNING, "{0}: excluding proxy -> {1}", new Object[]{Thread.currentThread().getName(), current_smart_proxy});

                    }

                    current_smart_proxy = _proxy_manager.getFastestProxy();

                    if (current_smart_proxy != null) {

                        String[] proxy_info = current_smart_proxy.split(":");

                        Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxy_info[0], Integer.parseInt(proxy_info[1])));

                        URL url = new URL(chunk_url);

                        con = (HttpURLConnection) url.openConnection(proxy);

                        getDownload().getMain_panel().getView().setSmartProxy(true);

                        getDownload().enableProxyTurboMode();

                    } else {

                        URL url = new URL(chunk_url);

                        con = (HttpURLConnection) url.openConnection();

                        getDownload().getMain_panel().getView().setSmartProxy(false);
                    }

                } else {

                    URL url = new URL(chunk_url);

                    if (MainPanel.isUse_proxy()) {

                        con = (HttpURLConnection) url.openConnection(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(MainPanel.getProxy_host(), MainPanel.getProxy_port())));

                        if (MainPanel.getProxy_user() != null && !"".equals(MainPanel.getProxy_user())) {

                            con.setRequestProperty("Proxy-Authorization", "Basic " + MiscTools.Bin2BASE64((MainPanel.getProxy_user() + ":" + MainPanel.getProxy_pass()).getBytes()));
                        }

                    } else {

                        con = (HttpURLConnection) url.openConnection();
                    }

                    current_smart_proxy = null;

                    getDownload().getMain_panel().getView().setSmartProxy(false);
                }

                con.setConnectTimeout(Download.HTTP_TIMEOUT);

                con.setReadTimeout(Download.HTTP_TIMEOUT);

                con.setRequestProperty("User-Agent", MainPanel.DEFAULT_USER_AGENT);

                if (getDownload().isError509()) {
                    getDownload().getView().set509Error(false);
                }

                http_error = 0;

                long chunk_reads = 0;

                chunk_error = true;

                timeout = false;

                slow_proxy = false;

                File tmp_chunk_file = null, chunk_file = null;

                Logger.getLogger(getClass().getName()).log(Level.INFO, "{0} Worker [{1}] is downloading chunk [{2}]!", new Object[]{Thread.currentThread().getName(), _id, chunk_id});

                try {

                    if (!_exit && !_download.isStopped()) {

                        int http_status = con.getResponseCode();

                        if (http_status != 200) {

                            Logger.getLogger(getClass().getName()).log(Level.INFO, "{0} Failed : HTTP error code : {1}", new Object[]{Thread.currentThread().getName(), http_status});

                            http_error = http_status;

                            if (http_error == 509 && MainPanel.isUse_smart_proxy()) {
                                getDownload().getView().set509Error(true);
                            }

                        } else {

                            try (InputStream is = new ThrottledInputStream(con.getInputStream(), _download.getMain_panel().getStream_supervisor())) {

                                byte[] buffer = new byte[DEFAULT_BYTE_BUFFER_SIZE];

                                int reads;

                                chunk_file = new File(_download.getDownload_path() + "/" + _download.getFile_name() + ".chunk" + chunk_id);

                                if (!chunk_file.exists() || chunk_file.length() != chunk_size) {

                                    tmp_chunk_file = new File(_download.getDownload_path() + "/" + _download.getFile_name() + ".chunk" + chunk_id + ".tmp");

                                    try (FileOutputStream tmp_chunk_file_os = new FileOutputStream(tmp_chunk_file)) {

                                        init_chunk_time = System.currentTimeMillis();

                                        paused = 0L;

                                        while (!_exit && !_download.isStopped() && !_download.getChunkmanager().isExit() && chunk_reads < chunk_size && (reads = is.read(buffer)) != -1) {

                                            tmp_chunk_file_os.write(buffer, 0, reads);

                                            chunk_reads += reads;

                                            _download.getPartialProgress().add((long) reads);

                                            _download.getProgress_meter().secureNotify();

                                            if (_download.isPaused() && !_download.isStopped()) {

                                                _download.pause_worker();

                                                pause_init_time = System.currentTimeMillis();

                                                secureWait();

                                                paused += System.currentTimeMillis() - pause_init_time;

                                            } else if (!_download.isPaused() && _download.getMain_panel().getDownload_manager().isPaused_all()) {

                                                _download.pause();

                                                _download.pause_worker();

                                                pause_init_time = System.currentTimeMillis();

                                                secureWait();

                                                paused += System.currentTimeMillis() - pause_init_time;
                                            }

                                        }

                                        finish_chunk_time = System.currentTimeMillis();
                                    }

                                } else {

                                    Logger.getLogger(getClass().getName()).log(Level.INFO, "{0} Worker [{1}] has RECOVERED PREVIOUS chunk [{2}]!", new Object[]{Thread.currentThread().getName(), _id, chunk_id});

                                    chunk_reads = chunk_size;

                                    _download.getPartialProgress().add(chunk_size);

                                    _download.getProgress_meter().secureNotify();
                                }
                            }
                        }

                        if (chunk_reads == chunk_size) {

                            Logger.getLogger(getClass().getName()).log(Level.INFO, "{0} Worker [{1}] has DOWNLOADED chunk [{2}]!", new Object[]{Thread.currentThread().getName(), _id, chunk_id});

                            if (tmp_chunk_file != null && chunk_file != null && (!chunk_file.exists() || chunk_file.length() != chunk_size)) {

                                if (chunk_file.exists()) {
                                    chunk_file.delete();
                                }

                                tmp_chunk_file.renameTo(chunk_file);
                            }

                            conta_error = 0;

                            chunk_error = false;

                            http_error = 0;

                            _download.getChunkmanager().secureNotify();

                            if (current_smart_proxy != null) {

                                //Proxy speed benchmark
                                long chunk_speed = Math.round((double) chunk_size / ((double) (finish_chunk_time - init_chunk_time - paused) / 1000));

                                if (chunk_speed < Math.round(((double) _download.getMain_panel().getGlobal_dl_speed().getMaxAverageGlobalSpeed() / _download.getMain_panel().getDownload_manager().calcTotalSlotsCount()) * SLOW_PROXY_PERC)) {

                                    Logger.getLogger(getClass().getName()).log(Level.INFO, "{0} Worker WARNING -> PROXY SPEED: {1}/s is SLOW", new Object[]{_id, formatBytes(chunk_speed)});

                                    slow_proxy = true;
                                }
                            }
                        }
                    }

                } catch (IOException ex) {

                    if (ex instanceof SocketTimeoutException) {
                        timeout = true;
                    }

                    Logger.getLogger(getClass().getName()).log(Level.SEVERE, null, ex);

                } finally {

                    if (chunk_error) {

                        if (!_exit && !_download.isStopped()) {

                            Logger.getLogger(getClass().getName()).log(Level.INFO, "{0} Worker [{1}] has FAILED downloading chunk [{2}]!", new Object[]{Thread.currentThread().getName(), _id, chunk_id});

                        }

                        if (tmp_chunk_file != null && tmp_chunk_file.exists()) {
                            tmp_chunk_file.delete();
                        }

                        _download.rejectChunkId(chunk_id);

                        if (chunk_reads > 0) {
                            _download.getPartialProgress().add(-1 * chunk_reads);
                            _download.getProgress_meter().secureNotify();
                        }

                        if (!_exit && !_download.isStopped() && !timeout && (http_error != 509 || !MainPanel.isUse_smart_proxy()) && http_error != 403) {

                            _error_wait = true;

                            _download.getView().updateSlotsStatus();

                            Thread.sleep(getWaitTimeExpBackOff(++conta_error) * 1000);

                            _error_wait = false;

                            _download.getView().updateSlotsStatus();
                        }
                    }

                    con.disconnect();
                }
            }

        } catch (ChunkInvalidException e) {

        } catch (OutOfMemoryError | Exception error) {

            _download.stopDownloader(error.getMessage());

            Logger.getLogger(getClass().getName()).log(Level.SEVERE, null, error.getMessage());

        }

        _download.stopThisSlot(this);

        _download.getChunkmanager().secureNotify();

        Logger.getLogger(getClass().getName()).log(Level.INFO, "{0} Worker [{1}]: bye bye", new Object[]{Thread.currentThread().getName(), _id});
    }
}
