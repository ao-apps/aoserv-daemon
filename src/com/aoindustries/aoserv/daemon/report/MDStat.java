/*
 * Copyright 2000-2013, 2014 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.report;

import com.aoindustries.util.AoArrays;

/**
 * Encapsulates the output of the /proc/mdstat file
 *
 * @author  AO Industries, Inc.
 */
final public class MDStat {

    final public int[] device_majors;       // *** In data
    final public int[] device_minors;       // *** In data
    final public int[] total_partitions;    // *** In data
    final public int[] active_partitions;   // *** In data

    public MDStat() {
        device_majors=device_minors=total_partitions=active_partitions = AoArrays.EMPTY_INT_ARRAY;
    }
}