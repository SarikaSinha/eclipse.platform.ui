/*******************************************************************************
 * Copyright (c) 2000, 2003 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Common Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/cpl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.ui.views.navigator;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.jface.viewers.AbstractTreeViewer;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.StructuredViewer;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.swt.widgets.Control;
import org.eclipse.ui.model.IWorkbenchAdapter;

/**
 * Provides tree contents for objects that have the IWorkbenchAdapter
 * adapter registered. 
 */
public class ProjectContentProvider implements ITreeContentProvider, IResourceChangeListener {
	protected Viewer viewer;

/* (non-Javadoc)
 * Method declared on IContentProvider.
 */
public void dispose() {
	if (viewer != null) {
		Object obj = viewer.getInput();
		if (obj instanceof IWorkspace) {
			IWorkspace workspace = (IWorkspace) obj;
			workspace.removeResourceChangeListener(this);
		} else
			if (obj instanceof IContainer) {
				IWorkspace workspace = ((IContainer) obj).getWorkspace();
				workspace.removeResourceChangeListener(this);
			}
	}
}
/**
 * Returns the implementation of IWorkbenchAdapter for the given
 * object.  Returns null if the adapter is not defined or the
 * object is not adaptable.
 */
protected IWorkbenchAdapter getAdapter(Object o) {
	if (!(o instanceof IAdaptable)) {
		return null;
	}
	return (IWorkbenchAdapter)((IAdaptable)o).getAdapter(IWorkbenchAdapter.class);
}
/* (non-Javadoc)
 * Method declared on ITreeContentProvider.
 */
public Object[] getChildren(Object element) {
	return new Object[0];
}
/* (non-Javadoc)
 * Method declared on IStructuredContentProvider.
 */
public Object[] getElements(Object element) {
	IWorkbenchAdapter adapter;
	
	adapter = getAdapter(getInput(element));
	if (adapter != null) {
		return adapter.getChildren(element);
	}
	return new Object[0];
}
IAdaptable getInput(Object element) {
	IResource resource = null;

	if (element instanceof IResource) {
		resource = (IResource) element;
	} else if (element instanceof IAdaptable){
		resource = (IResource) ((IAdaptable) element).getAdapter(IResource.class);
	}
	if (resource != null) {
		switch (resource.getType()) {
			case IResource.FILE :
				return resource.getParent();
			case IResource.FOLDER :
			case IResource.PROJECT :
			case IResource.ROOT :
				return (IContainer) resource;
			default :
				// Unknown resource type.  Fall through.
				break;
		}
	}
	return ResourcesPlugin.getWorkspace().getRoot();	
}
/* (non-Javadoc)
 * Method declared on ITreeContentProvider.
 */
public Object getParent(Object element) {
	IWorkbenchAdapter adapter = getAdapter(element);
	if (adapter != null) {
	    return adapter.getParent(element);
	}
	return null;
}
/* (non-Javadoc)
 * Method declared on ITreeContentProvider.
 */
public boolean hasChildren(Object element) {
	return false;
}
/* (non-Javadoc)
 * Method declared on IContentProvider.
 */
public void inputChanged(Viewer viewer, Object oldInput, Object newInput) {
	this.viewer = viewer;
	IWorkspace oldWorkspace = null;
	IWorkspace newWorkspace = null;
	if (oldInput instanceof IWorkspace) {
		oldWorkspace = (IWorkspace) oldInput;
	}
	else if (oldInput instanceof IContainer) {
		oldWorkspace = ((IContainer) oldInput).getWorkspace();
	}
	if (newInput instanceof IWorkspace) {
		newWorkspace = (IWorkspace) newInput;
	} else if (newInput instanceof IContainer) {
		newWorkspace = ((IContainer) newInput).getWorkspace();
	}
	if (oldWorkspace != newWorkspace) {
		if (oldWorkspace != null) {
			oldWorkspace.removeResourceChangeListener(this);
		}
		if (newWorkspace != null) {
			newWorkspace.addResourceChangeListener(this, IResourceChangeEvent.POST_CHANGE);
		}
	}
}
/**
 * Process a resource delta.  
 */
protected void processDelta(IResourceDelta delta) {
	// This method runs inside a syncExec.  The widget may have been destroyed
	// by the time this is run.  Check for this and do nothing if so.
	Control ctrl = viewer.getControl();
	if (ctrl == null || ctrl.isDisposed())
		return;

	// Get the affected resource
	IResource resource = delta.getResource();

	// If any children have changed type, just do a full refresh of this parent,
	// since a simple update on such children won't work, 
	// and trying to map the change to a remove and add is too dicey.
	// The case is: folder A renamed to existing file B, answering yes to overwrite B.
	IResourceDelta[] affectedChildren =
		delta.getAffectedChildren(IResourceDelta.CHANGED);
	for (int i = 0; i < affectedChildren.length; i++) {
		if ((affectedChildren[i].getFlags() & IResourceDelta.TYPE) != 0) {
			((StructuredViewer) viewer).refresh(resource);
			return;
		}
	}

	// Check the flags for changes the Navigator cares about.
	// See ResourceLabelProvider for the aspects it cares about.
	// Notice we don't care about F_CONTENT or F_MARKERS currently.
	int changeFlags = delta.getFlags();
	if ((changeFlags
		& (IResourceDelta.OPEN | IResourceDelta.SYNC))
		!= 0) {
		((StructuredViewer) viewer).update(resource, null);
	}
	// Replacing a resource may affect its label and its children
	if ((changeFlags & IResourceDelta.REPLACED) != 0) {
		((StructuredViewer) viewer).refresh(resource, true);
		return;
	}

	// Handle changed children .
	for (int i = 0; i < affectedChildren.length; i++) {
		processDelta(affectedChildren[i]);
	}

	// Process removals before additions, to avoid multiple equal elements in the viewer.

	// Handle removed children. Issue one update for all removals.
	affectedChildren = delta.getAffectedChildren(IResourceDelta.REMOVED);
	if (affectedChildren.length > 0) {
		Object[] affected = new Object[affectedChildren.length];
		for (int i = 0; i < affectedChildren.length; i++)
			affected[i] = affectedChildren[i].getResource();
		if (viewer instanceof AbstractTreeViewer) {
			((AbstractTreeViewer) viewer).remove(affected);
		} else {
			((StructuredViewer) viewer).refresh(resource);
		}
	}

	// Handle added children. Issue one update for all insertions.
	affectedChildren = delta.getAffectedChildren(IResourceDelta.ADDED);
	if (affectedChildren.length > 0) {
		Object[] affected = new Object[affectedChildren.length];
		for (int i = 0; i < affectedChildren.length; i++)
			affected[i] = affectedChildren[i].getResource();
		if (viewer instanceof AbstractTreeViewer) {
			((AbstractTreeViewer) viewer).add(resource, affected);
		}
		else {
			((StructuredViewer) viewer).refresh(resource);
		}
	}
}
/**
 * The workbench has changed.  Process the delta and issue updates to the viewer,
 * inside the UI thread.
 *
 * @see IResourceChangeListener#resourceChanged(org.eclipse.core.resources.IResourceChangeEvent)
 */
public void resourceChanged(final IResourceChangeEvent event) {
	final IResourceDelta delta = event.getDelta();
	Control ctrl = viewer.getControl();
	if (ctrl != null && !ctrl.isDisposed()) {
		// Do a sync exec, not an async exec, since the resource delta
		// must be traversed in this method.  It is destroyed
		// when this method returns.
		ctrl.getDisplay().syncExec(new Runnable() {
			public void run() {
				processDelta(delta);
			}
		});
	}
}
}