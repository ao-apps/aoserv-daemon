/*
 * Copyright 2000-2013, 2014 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.report;

import com.aoindustries.util.AoArrays;

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
        deviceIDs = AoArrays.EMPTY_STRING_ARRAY;
        rxBytes=rxPackets=rxErrors=rxDrop=rxFIFO=rxFrame=rxCompress=rxMulticast=txBytes=txPackets=txErrors=txDrop=txFIFO=txColls=txCarrier=txCompressed = AoArrays.EMPTY_LONG_ARRAY;
    }
}