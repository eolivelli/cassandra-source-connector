/**
 * Copyright DataStax, Inc 2021.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datastax.oss.common.sink.util;

/** Utility methods for manipulating strings. */
public class StringUtil {
  /** This is a utility class and should never be instantiated. */
  private StringUtil() {}

  public static String singleQuote(String s) {
    return "'" + s + "'";
  }

  public static boolean isEmpty(String s) {
    return s == null || s.isEmpty();
  }
}
