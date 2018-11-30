/*
 * Copyright 2008-2012, 2015 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.util;

import com.aoindustries.io.unix.Stat;
import com.aoindustries.io.unix.UnixFile;
import com.aoindustries.lang.ObjectUtils;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * Manages PrivateTomcatSite version 5.5.X configurations.
 *
 * @author  AO Industries, Inc.
 */
public class UpgradeSymlink {

    private final String linkPath;
    private final String oldLinkTarget;
    private final String newLinkTarget;

    /**
     * @param linkPath
     * @param oldLinkTarget  if <code>null</code> the link will be created if missing
     * @param newLinkTarget
     */
    public UpgradeSymlink(String linkPath, String oldLinkTarget, String newLinkTarget) {
        if(ObjectUtils.equals(oldLinkTarget, newLinkTarget)) throw new IllegalArgumentException("oldLinkTarget=newLinkTarget: "+oldLinkTarget);
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
     * Gets the new link target or <code>null</code> if should not exist.
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
        if(oldLinkTarget==null) {
            if(!linkStat.exists()) {
				try {
					link.symLink(newLinkTarget);
					linkStat = link.getStat();
					needsRestart = true;
				} catch(FileNotFoundException e) {
					System.err.println("FileNotFound: link="+link);
					throw e;
				}
            }
        } else if(linkStat.exists()) {
            if(linkStat.isSymLink()) {
                String target = link.readLink();
                if(target.equals(oldLinkTarget)) {
                    link.delete();
                    if(newLinkTarget!=null) link.symLink(newLinkTarget);
                    linkStat = link.getStat();
                    needsRestart = true;
                }
            }
        }
        // Check ownership
        if(linkStat.exists() && (linkStat.getUid()!=uid || linkStat.getGid()!=gid)) link.chown(uid, gid);
        return needsRestart;
    }
}
