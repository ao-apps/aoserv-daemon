package com.aoindustries.aoserv.daemon.report;

/*
 * Copyright 2000-2006 by AO Industries, Inc.,
 * 816 Azalea Rd, Mobile, Alabama, 36693, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.profiler.*;
import java.io.*;

/**
 * Encapsulates the output of the /bin/df command.
 *
 * @author  AO Industries, Inc.
 */
final public class DF {

    public final int[] device_majors;
    public final int[] device_minors;
    public final long[] totals;
    public final long[] useds;
    public final long[] frees;

    public DF() {
        Profiler.startProfile(Profiler.FAST, DF.class, "<init>()", null);
        try {
            device_majors=device_minors=new int[0];
            totals=useds=frees=new long[0];
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }
}