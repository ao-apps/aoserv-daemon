/*
 * Copyright 2000-2013 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.report;

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
        deviceIDs=new String[0];
        rxBytes=rxPackets=rxErrors=rxDrop=rxFIFO=rxFrame=rxCompress=rxMulticast=txBytes=txPackets=txErrors=txDrop=txFIFO=txColls=txCarrier=txCompressed=new long[0];
    }
}