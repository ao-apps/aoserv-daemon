package com.aoindustries.aoserv.daemon.report;

/*
 * Copyright 2000-2008 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
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
        device_majors=device_minors=new int[0];
        totals=useds=frees=new long[0];
    }
}