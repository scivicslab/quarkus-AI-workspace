package com.scivicslab.aiworkspace.model;

/** A tool's favicon, read from its own jar, with the content type to serve it as. */
public record IconData(byte[] bytes, String contentType) {}
