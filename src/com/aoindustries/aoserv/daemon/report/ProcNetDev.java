package com.aoindustries.aoserv.daemon.report;

/*
 * Copyright 2000-2008 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.profiler.*;
import java.io.*;

/**
 * Encapsulates the output of the /proc/net/dev file.
 *
 * @author  AO Industries, Inc.
 */
public final class ProcNetDev {

    final public String[] deviceIDs;
    final public long[]
        rxBytes,
        rxPackets,
        rxErrors,
        rxDrop,
        rxFIFO,
        rxFrame,
        rxCompress,
        rxMulticast,
        txBytes,
        txPackets,
        txErrors,
        txDrop,
        txFIFO,
        txColls,
        txCarrier,
        txCompressed
    ;

    public ProcNetDev() {
        Profiler.startProfile(Profiler.FAST, ProcNetDev.class, "<init>()", null);
        try {
            deviceIDs=new String[0];
            rxBytes=rxPackets=rxErrors=rxDrop=rxFIFO=rxFrame=rxCompress=rxMulticast=txBytes=txPackets=txErrors=txDrop=txFIFO=txColls=txCarrier=txCompressed=new long[0];
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }
}