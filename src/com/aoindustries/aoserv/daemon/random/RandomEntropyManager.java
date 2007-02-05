package com.aoindustries.aoserv.daemon.random;

/*
 * Copyright 2004-2007 by AO Industries, Inc.,
 * 816 Azalea Rd, Mobile, Alabama, 36693, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.*;
import com.aoindustries.aoserv.daemon.*;
import com.aoindustries.io.unix.linux.*;
import com.aoindustries.profiler.*;
import com.aoindustries.util.*;
import java.io.*;
import java.sql.*;
import java.util.*;

/**
 * Watches the amount of entropy available in the system, obtains entropy from the master when running low,
 * provides entropy to the master when has extra.
 *
 * @author  AO Industries, Inc.
 */
public final class RandomEntropyManager implements Runnable {

    /**
     * The minimum delay between scans.
     */
    public static final int MIN_DELAY=1*1000;
    
    /**
     * The maximum delay between scans.
     */
    public static final int MAX_OBTAIN_DELAY=15*1000;

    /**
     * The maximum delay between scans.
     */
    public static final int MAX_PROVIDE_DELAY=5*60*1000;

    /**
     * The delay after an error occurs.
     */
    public static final int ERROR_DELAY=60*1000;

    /**
     * The number of bits available where will provide to master server.
     */
    public static final int PROVIDE_THRESHOLD=3584;
    
    /**
     * The number of bits available after providing to the master server.
     */
    public static final int DESIRED_BITS=3072;

    /**
     * The number of bits available where will obtain from master server.
     */
    public static final int OBTAIN_THRESHOLD=2048;

    private static Thread thread;

    private RandomEntropyManager() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, RandomEntropyManager.class, "<init>()", null);
        Profiler.endProfile(Profiler.INSTANTANEOUS);
    }
    
    public void run() {
        Profiler.startProfile(Profiler.UNKNOWN, RandomEntropyManager.class, "run()", null);
        try {
            while(true) {
                try {
                    AOServConnector conn=AOServDaemon.getConnector();
                    boolean lastObtain=true;
                    while(true) {
                        int sleepyTime;
                        int entropyAvail=DevRandom.getEntropyAvail();
                        if(entropyAvail<=OBTAIN_THRESHOLD) {
                            lastObtain=true;
                            int bitsNeeded=DESIRED_BITS-entropyAvail;
                            int bytesNeeded=bitsNeeded>>>3;
                            if(bytesNeeded>BufferManager.BUFFER_SIZE) bytesNeeded=BufferManager.BUFFER_SIZE;
                            byte[] buff=BufferManager.getBytes();
                            try {
                                int obtained=conn.getMasterEntropy(buff, bytesNeeded);
                                if(obtained>0) {
                                    if(obtained==BufferManager.BUFFER_SIZE) DevRandom.addEntropy(buff);
                                    else {
                                        byte[] newBuff=new byte[obtained];
                                        System.arraycopy(buff, 0, newBuff, 0, obtained);
                                        DevRandom.addEntropy(newBuff);
                                    }
                                }
                            } finally {
                                BufferManager.release(buff);
                            }
                            // Sleep proportional to the amount of pool needed
                            sleepyTime=
                                MIN_DELAY
                                +(int)(
                                    (
                                        (double)entropyAvail/(double)DESIRED_BITS
                                    )*(MAX_OBTAIN_DELAY-MIN_DELAY)
                                )
                            ;
                        } else {
                            long masterNeeded=-1;
                            if(entropyAvail>=PROVIDE_THRESHOLD) {
                                lastObtain=false;
                                int provideBits=entropyAvail-DESIRED_BITS;
                                int provideBytes=provideBits>>>3;
                                masterNeeded=conn.getMasterEntropyNeeded();
                                if(provideBytes>masterNeeded) provideBytes=(int)masterNeeded;
                                if(provideBytes>0) {
                                    if(provideBytes>BufferManager.BUFFER_SIZE) provideBytes=BufferManager.BUFFER_SIZE;
                                    if(AOServDaemon.DEBUG) System.err.println("DEBUG: RandomEntropyManager: Providing "+provideBytes+" bytes ("+(provideBytes<<3)+" bits) of entropy to master server");
                                    byte[] buff=BufferManager.getBytes();
                                    try {
                                        DevRandom.nextBytesStatic(buff, 0, provideBytes);
                                        conn.addMasterEntropy(buff, provideBytes);
                                    } finally {
                                        BufferManager.release(buff);
                                    }
                                }
                            }
                            if(lastObtain) sleepyTime=MAX_OBTAIN_DELAY;
                            else {
                                // Sleep for the longest delay or shorter if master needs more entropy
                                if(masterNeeded==-1) masterNeeded=conn.getMasterEntropyNeeded();
                                if(masterNeeded>0) {
                                    // Sleep proportional to the amount of master pool needed
                                    sleepyTime=
                                        MIN_DELAY
                                        +(int)(
                                            (
                                                1.0d-(
                                                    (double)masterNeeded/(double)AOServConnector.MASTER_ENTROPY_POOL_SIZE
                                                )
                                            )*(MAX_PROVIDE_DELAY-MIN_DELAY)
                                        )
                                    ;
                                } else sleepyTime=MAX_PROVIDE_DELAY;
                            }
                        }

                        try {
                            Thread.sleep(sleepyTime);
                        } catch(InterruptedException err) {
                            AOServDaemon.reportWarning(err, null);
                        }
                    }
                } catch(ThreadDeath TD) {
                    throw TD;
                } catch(Throwable T) {
                    AOServDaemon.reportError(T, null);
                    try {
                        Thread.sleep(ERROR_DELAY);
                    } catch(InterruptedException err) {
                        AOServDaemon.reportWarning(err, null);
                    }
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }
    
    public static void start() throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, RandomEntropyManager.class, "start()", null);
        try {
            // Avoid random manager when in failover mode
            if(AOServDaemonConfiguration.isManagerEnabled(RandomEntropyManager.class) && AOServDaemon.getThisAOServer().getFailoverServer()==null) {
                if(thread==null) {
                    synchronized(System.out) {
                        if(thread==null) {
                            System.out.print("Starting RandomEntropyManager: ");
                            (thread=new Thread(new RandomEntropyManager())).start();
                            System.out.println("Done");
                        }
                    }
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }
}
