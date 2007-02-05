package com.aoindustries.aoserv.daemon.report;

/*
 * Copyright 2000-2007 by AO Industries, Inc.,
 * 816 Azalea Rd, Mobile, Alabama, 36693, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.profiler.*;
import java.io.*;

/**
 * Encapsulates the output of the /proc/partitions file
 *
 * @author  AO Industries, Inc.
 */
final public class ProcPartitions {

    final public int[] device_majors;
    final public int[] device_minors;
    final public long[]
        blocks,
        rios,
        rmerges,
        rsect,
        ruse,
        wios,
        wmerge,
        wsect,
        wuse
    ;
    final public int[] runnings;
    final public long[]
        uses,
        aveq
    ;

    public ProcPartitions() {
        Profiler.startProfile(Profiler.FAST, ProcPartitions.class, "<init>()", null);
        try {
            device_majors=device_minors=runnings=new int[0];
            blocks=rios=rmerges=rsect=ruse=wios=wmerge=wsect=wuse=uses=aveq=new long[0];
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }
}