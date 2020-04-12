/*
 * Copyright 2000-2013, 2014, 2020 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.report;

import com.aoindustries.lang.EmptyArrays;

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
		deviceIDs = EmptyArrays.EMPTY_STRING_ARRAY;
		rxBytes=rxPackets=rxErrors=rxDrop=rxFIFO=rxFrame=rxCompress=rxMulticast=txBytes=txPackets=txErrors=txDrop=txFIFO=txColls=txCarrier=txCompressed = EmptyArrays.EMPTY_LONG_ARRAY;
	}
}
