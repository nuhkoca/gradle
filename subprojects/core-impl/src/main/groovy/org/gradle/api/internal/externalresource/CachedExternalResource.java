/*
 * Copyright 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.internal.externalresource;

import org.gradle.api.Nullable;
import org.gradle.util.hash.HashValue;

import java.io.File;
import java.util.Date;

public interface CachedExternalResource {

    boolean isMissing();

    @Nullable
    File getCachedFile();

    long getCachedAt();

    /**
     * Always the actual content length of the cached file
     *
     * @return
     */
    long getContentLength();

    /**
     * Always the actual checksum of the cached file
     *
     * @return
     */
    HashValue getSha1();

    @Nullable
    ExternalResourceMetaData getExternalResourceMetaData();

    /**
     * Null safe shortcut for getExternalResourceMetaData().getLastModified();
     * @return
     */
    Date getExternalLastModified();

    long getExternalLastModifiedAsTimestamp();

}
