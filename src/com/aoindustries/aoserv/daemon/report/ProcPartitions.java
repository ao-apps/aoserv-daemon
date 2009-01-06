package com.aoindustries.aoserv.daemon.report;

/*
 * Copyright 2000-2009 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
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
        device_majors=device_minors=runnings=new int[0];
        blocks=rios=rmerges=rsect=ruse=wios=wmerge=wsect=wuse=uses=aveq=new long[0];
    }
}