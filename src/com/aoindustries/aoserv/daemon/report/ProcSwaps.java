package com.aoindustries.aoserv.daemon.report;

/*
 * Copyright 2000-2008 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.profiler.*;
import java.io.*;

/**
 * Encapsulates the output of the /proc/swaps file
 *
 * @author  AO Industries, Inc.
 */
final public class ProcSwaps {

    final public int[]
        device_majors,
        device_minors,
        totals,
        useds
    ;

    public ProcSwaps() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, DF.class, "ProcSwaps()", null);
        try {
            device_majors=device_minors=totals=useds=new int[0];
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }
}