package com.aoindustries.aoserv.daemon.report;

/*
 * Copyright 2000-2006 by AO Industries, Inc.,
 * 816 Azalea Rd, Mobile, Alabama, 36693, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.profiler.*;
import java.io.*;

/**
 * Encapsulates the output of the /proc/sys/fs files
 *
 * @author  AO Industries, Inc.
 */
public final class ProcSys {

    final public int
        files_allocated,
        files_used,
        files_max,
        nr_inodes,
        nr_free_inodes,
        inode_preshrink,
        msgmax,
        msgmnb,
        msgmni,
        nr_msg,
        entropy_avail,
        rtsig_max,
        rtsig_nr,
        semmsl,
        semmns,
        semopm,
        semmni,
        nr_sem,
        shmall,
        shmmax,
        shmmni,
        nr_shm,
        shm_used,
        threads_max
    ;

    public ProcSys() {
        Profiler.startProfile(Profiler.FAST, ProcSys.class, "<init>()", null);
        try {
            files_allocated
            =files_used
            =files_max
            =nr_inodes
            =nr_free_inodes
            =inode_preshrink
            =msgmax
            =msgmnb
            =msgmni
            =nr_msg
            =entropy_avail
            =rtsig_max
            =rtsig_nr
            =semmsl
            =semmns
            =semopm
            =semmni
            =nr_sem
            =shmall
            =shmmax
            =shmmni
            =nr_shm
            =shm_used
            =threads_max
            =0;
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }
}