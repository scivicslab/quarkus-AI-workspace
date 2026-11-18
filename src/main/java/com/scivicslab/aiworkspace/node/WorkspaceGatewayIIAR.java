package com.scivicslab.aiworkspace.node;

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * The actor {@value WorkspaceNode#GATEWAY}: the one actor of AI-workspace that other nodes may call, and only for the
 * actions of {@link WorkspaceNode#ALLOWED}. Each action hands its JSON argument to {@link WorkspaceGateway}.
 */
public class WorkspaceGatewayIIAR extends IIActorRef<WorkspaceGateway> {

    public WorkspaceGatewayIIAR(String actorName, WorkspaceGateway gateway, IIActorSystem system) {
        super(actorName, gateway, system);
    }

    @Action("listInstances")
    public ActionResult listInstances(String args) { return object.listInstances(args); }

    @Action("listCatalog")
    public ActionResult listCatalog(String args) { return object.listCatalog(args); }

    @Action("listNodes")
    public ActionResult listNodes(String args) { return object.listNodes(args); }

    @Action("announceNode")
    public ActionResult announceNode(String args) { return object.announceNode(args); }
}
