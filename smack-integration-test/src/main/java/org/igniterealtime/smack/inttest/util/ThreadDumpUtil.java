/**
 *
 * Copyright 2024 Florian Schmaus
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.igniterealtime.smack.inttest.util;

import java.io.IOException;
import java.lang.management.ManagementFactory;

public class ThreadDumpUtil {

    public static String threadDump() {
        var sb = new StringBuilder();
        try {
            threadDump(sb);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        return sb.toString();
    }

    public static void threadDump(Appendable appendable) throws IOException {
        var bean = ManagementFactory.getThreadMXBean();
        var infos = bean.dumpAllThreads(true, true);
        for (var info : infos) {
            appendable.append(info.toString());
        }
    }
}
