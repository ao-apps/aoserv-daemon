package com.aoindustries.aoserv.daemon.util;

import com.aoindustries.io.unix.Stat;
import com.aoindustries.io.unix.UnixFile;

/*
 * Copyright 2008 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import java.io.IOException;

/**
 * Manages HttpdTomcatStdSite version 5.5.X configurations.
 *
 * @author  AO Industries, Inc.
 */
public class UpgradeSymlink {

    private final String linkPath;
    private final String oldLinkTarget;
    private final String newLinkTarget;

    public UpgradeSymlink(String linkPath, String oldLinkTarget, String newLinkTarget) {
        if(oldLinkTarget.equals(newLinkTarget)) throw new IllegalArgumentException("oldLinkTarget=newLinkTarget: "+oldLinkTarget);
        this.linkPath = linkPath;
        this.oldLinkTarget = oldLinkTarget;
        this.newLinkTarget = newLinkTarget;
    }

    /**
     * Gets the relative path to the symlink.
     */
    public String getLinkPath() {
        return linkPath;
    }
    
    /**
     * Gets the old target.
     */
    public String getOldLinkTarget() {
        return oldLinkTarget;
    }
    
    /**
     * Gets the new link target.
     */
    public String getNewLinkTarget() {
        return newLinkTarget;
    }
    
    /**
     * Changes the target of the symlink if needed.  Will also reset the UID
     * and GID if they mismatch.
     * 
     * @return  <code>true</code> if link modified.  UID and GID changes alone will not
     *          count as a change.
     */
    public boolean upgradeLinkTarget(UnixFile baseDirectory, int uid, int gid) throws IOException {
        boolean needsRestart = false;
        UnixFile link = new UnixFile(baseDirectory.getPath()+"/"+linkPath);
        Stat linkStat = link.getStat();
        if(linkStat.exists()) {
            if(linkStat.isSymLink()) {
                String target = link.readLink();
                if(target.equals(oldLinkTarget)) {
                    link.delete();
                    link.symLink(newLinkTarget);
                    link.getStat(linkStat);
                    needsRestart = true;
                }
            }
            // Check ownership
            if(linkStat.getUID()!=uid || linkStat.getGID()!=gid) link.chown(uid, gid);
        }
        return needsRestart;
    }
}
