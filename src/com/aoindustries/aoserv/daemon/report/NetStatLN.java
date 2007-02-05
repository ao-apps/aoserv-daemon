package com.aoindustries.aoserv.daemon.report;

/*
 * Copyright 2000-2007 by AO Industries, Inc.,
 * 816 Azalea Rd, Mobile, Alabama, 36693, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.profiler.*;
import java.io.*;

/**
 * Encapsulates the output of the /bin/netstat -ln command.
 *
 * @author  AO Industries, Inc.
 */
final public class NetStatLN {

    final public String[] deviceIDs;
    final public int[] listens;

    public NetStatLN() {
        Profiler.startProfile(Profiler.FAST, NetStatLN.class, "<init>()", null);
        try {
            deviceIDs=new String[0];
            listens=new int[0];
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }
}