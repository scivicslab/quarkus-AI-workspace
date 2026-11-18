package com.scivicslab.aiworkspace.model;

/**
 * What a tool tells AI-workspace when it joins the distributed actor system: its node name, its kind, the port it
 * takes messages on, and the HTTP port of the instance it is (ActorNodesForWorkspaceAndPortal_261118_oo01, question 11).
 * The host is always 127.0.0.1: AI-workspace runs its tools on its own machine.
 */
public record NodeAnnouncement(String name, String kind, int actorPort, int httpPort) {}
