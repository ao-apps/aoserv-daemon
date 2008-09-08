package com.aoindustries.aoserv.daemon.report;

/*
 * Copyright 2000-2008 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.profiler.*;
import java.io.*;

/**
 * @author  AO Industries, Inc.
 */
abstract public class DBReportData {

    public int numUsers;

    public DBReportData() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, DBReportData.class, "<init>()", null);
        Profiler.endProfile(Profiler.INSTANTANEOUS);
    }

    public String toString() {
        Profiler.startProfile(Profiler.FAST, DBReportData.class, "toString()", null);
        try {
            return
                getClass().getName()
                +"?numUsers="+numUsers
            ;
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }
}