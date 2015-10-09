package org.wlou.jbdownloader;

import java.io.IOException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

public class Download {

    public enum Status
    {
        NEW,
        INIT,
        ACTIVE,
        PAUSED,
        COMPLETED,
        ERROR
    }

    public final AtomicReference<Status> CurrentStatus;
    public final AtomicReference<String> Information;

    public static class TransferBlock {
        public long offset;
        public ByteBuffer buffer;
        public Future<Integer> read;
    }

    public Download(URL what, Path where) throws IOException {
        CurrentStatus = new AtomicReference<>(Status.NEW);
        Information = new AtomicReference<>("TODO:"); //TODO:
        this.what = what;
        this.where = where;
        /*
        output = AsynchronousFileChannel.open(
            this.where,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE
        );
        */
    }

    public URL getWhat() {
        return what;
    }

    public Path getWhere() {
        return where;
    }
/*
    public AsynchronousFileChannel getOutput() {
        return output;
    }
*/
    private final URL what;
    private final Path where;

//    private final AsynchronousFileChannel output;
}
