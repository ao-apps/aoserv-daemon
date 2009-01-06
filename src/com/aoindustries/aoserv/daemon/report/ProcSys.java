package com.aoindustries.aoserv.daemon.report;

/*
 * Copyright 2000-2009 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
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
    }
}