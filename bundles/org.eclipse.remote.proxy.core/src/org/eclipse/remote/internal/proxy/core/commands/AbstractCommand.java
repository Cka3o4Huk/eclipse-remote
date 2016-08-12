/*******************************************************************************
 * Copyright (c) 2016 Oak Ridge National Laboratory and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/
package org.eclipse.remote.internal.proxy.core.commands;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.remote.proxy.core.exceptions.ProxyException;

public abstract class AbstractCommand<T> {
	public abstract T exec(IProgressMonitor monitor) throws ProxyException;
}
