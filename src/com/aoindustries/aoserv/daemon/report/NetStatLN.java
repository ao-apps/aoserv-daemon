/*
 * Copyright 2000-2014 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.report;

import com.aoindustries.util.AoArrays;

/**
 * Encapsulates the output of the /bin/netstat -ln command.
 *
 * @author  AO Industries, Inc.
 */
final public class NetStatLN {

    final public String[] deviceIDs;
    final public int[] listens;

    public NetStatLN() {
        deviceIDs = AoArrays.EMPTY_STRING_ARRAY;
        listens = AoArrays.EMPTY_INT_ARRAY;
    }
}