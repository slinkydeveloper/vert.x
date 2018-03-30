/*
 * Copyright (c) 2011-2017 Contributors to the Eclipse Foundation
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
 * which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */

package io.vertx.core.json.impl;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.json.JsonPointer;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * @author Francesco Guardiani @slinkydeveloper
 */
public class JsonPointerImpl implements JsonPointer {

  final public static Pattern VALID_POINTER_PATTERN = Pattern.compile("([0-9]*|-)(\\/([\\u0000-\\u002E]|[\\u0030-\\u007D]|[\\u007F-\\u10FFFF]|\\~0|\\~1)*)*");

  // Empty means a pointer to root
  LinkedList<String> undecodedTokens;

  public JsonPointerImpl(List<String> tokens) {
    if (tokens.size() == 0 || tokens.size() == 1 && "".equals(tokens.get(0)))
      undecodedTokens = null;
    else
      undecodedTokens = new LinkedList<>(tokens);
  }

  public JsonPointerImpl(String pointer) {
    undecodedTokens = parse(pointer);
  }

  public JsonPointerImpl() {
    undecodedTokens = new LinkedList<>();
    undecodedTokens.add(""); // Root
  }

  private LinkedList<String> parse(String pointer) {
    if ("".equals(pointer))
      return null;
    if (VALID_POINTER_PATTERN.matcher(pointer).matches()) {
      return Arrays
        .stream(pointer.split("/", -1))
        .map(this::unescape)
        .collect(Collectors.toCollection(LinkedList::new));
    } else
      throw new IllegalArgumentException("The provided pointer is not a valid JSON Pointer");
  }

  private String escape(String path) {
    return path.replace("~", "~0")
      .replace("/", "~1");
  }

  private String unescape(String path) {
    return path.replace("~1", "/") // https://tools.ietf.org/html/rfc6901#section-4
      .replace("~0", "~");
  }

  private boolean isRootPointer() {
    return undecodedTokens == null || undecodedTokens.size() == 0 || (undecodedTokens.size() == 1 && "".equals(undecodedTokens.get(0)));
  }

  @Override
  public String build() {
    if (isRootPointer())
      return "";
    else
      return String.join("/", undecodedTokens.stream().map(this::escape).collect(Collectors.toList()));
  }

  @Override
  public String buildURI() {
    if (isRootPointer()) {
      return "#";
    } else if (undecodedTokens.size() >= 1 && "".equals(undecodedTokens.get(0))) {
      // If the first token is the empty token we should remove it!
      return "#" + undecodedTokens.subList(1, undecodedTokens.size())
        .stream()
        .map(s -> {
          try {
            return "/" + URLEncoder.encode(s, "UTF-8");
          } catch (UnsupportedEncodingException e) {
            e.printStackTrace(); //WHY THAT
            throw new AssertionError("UTF-8 is unknown");
          }
        })
        .reduce("", String::concat);
    } else
      return "#" + undecodedTokens
        .stream()
        .map(s -> {
          try {
            return "/" + URLEncoder.encode(s, "UTF-8");
          } catch (UnsupportedEncodingException e) {
            e.printStackTrace(); //WHY THAT
            throw new AssertionError("UTF-8 is unknown");
          }
        })
        .reduce("", String::concat);
  }

  @Override
  public JsonPointer append(String path) {
    undecodedTokens.add(path);
    return this;
  }

  @Override
  public JsonPointer append(List<String> paths) {
    undecodedTokens.addAll(paths);
    return this;
  }

  @Override
  public Object query(Object input) {
    // I should threat this as a special condition because the empty string can be a json obj key!
    if (isRootPointer())
      return input;
    else {
      Object v = walkTillLastElement(input);
      String lastKey = undecodedTokens.get(undecodedTokens.size() - 1);
      if (v instanceof JsonObject) {
        return ((JsonObject)v).getValue(lastKey);
      } else if (v instanceof JsonArray && !"-".equals(lastKey)) {
        try {
          return ((JsonArray)v).getValue(Integer.parseInt(lastKey));
        } catch (Exception e) {
          return null;
        }
      } else
        return null;
    }
  }

  @Override
  public boolean writeObject(JsonObject input, Object value) {
    return write(input, value);
  }

  @Override
  public boolean writeArray(JsonArray input, Object value) {
    return write(input, value);
  }

  @Override
  public JsonPointer copy() {
    return new JsonPointerImpl(new LinkedList<>(this.undecodedTokens));
  }

  private boolean write(Object input, Object value) {
    if (isRootPointer())
      throw new IllegalStateException("writeObject() doesn't support root pointers");
    else {
      Object lastElem = walkTillLastElement(input);
      return lastElem != null && writeLastElement(lastElem, value);
    }
  }

  private Object walkTillLastElement(Object input) {
    for (int i = 0; i < undecodedTokens.size() - 1; i++) {
      String k = undecodedTokens.get(i);
      if (i == 0 && "".equals(k)) {
        continue; // Avoid errors with root empty string
      } else if (input instanceof JsonObject) {
        JsonObject obj = (JsonObject) input;
        if (obj.containsKey(k))
          input = obj.getValue(k);
        else
          return null; // Missing array element
      } else if (input instanceof JsonArray) {
        JsonArray arr = (JsonArray) input;
        if (k.equals("-"))
          return null; // - is useful only on write!
        else {
          try {
            input = arr.getValue(Integer.parseInt(k));
          } catch (Exception e) {
            return null;
          }
        }
      } else {
        return null;
      }
    }
    return input;
  }

  private boolean writeLastElement(Object input, Object value) {
    String lastKey = undecodedTokens.get(undecodedTokens.size() - 1);
    if (input instanceof JsonObject) {
      ((JsonObject)input).put(lastKey, value);
      return true;
    } else if (input instanceof JsonArray) {
      if ("-".equals(lastKey)) { // Append to end
        ((JsonArray)input).add(value);
        return true;
      } else { // We have a index
        try {
          ((JsonArray)input).set(Integer.parseInt(lastKey), value);
          return true;
        } catch (Exception e) {
          return false;
        }
      }
    } else
      return false;
  }

  @Override
  public String toString() {
    return this.build();
  }
}
