/*******************************************************************************
 * Copyright (c) 2013 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package com.ibm.ws.ejbcontainer.osgi.internal.ejb;

import java.io.Externalizable;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.io.Serializable;

import javax.ejb.TimedObject;
import javax.ejb.Timer;

public class TestDefaultMessageListenerXML implements Serializable, Externalizable, TimedObject, LocalIntf1 {
    @Override
    public void writeExternal(ObjectOutput out) {}

    @Override
    public void readExternal(ObjectInput in) {}

    @Override
    public void ejbTimeout(Timer timer) {}
}