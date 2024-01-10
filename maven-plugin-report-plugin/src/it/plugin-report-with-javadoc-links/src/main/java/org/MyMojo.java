package org;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.util.Collection;
import java.util.Map;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Some description
 * 
 * @see java.util.Collections
 *
 */
@Mojo( name = "test" )
public class MyMojo
    extends AbstractMojo
{

    /**
     * beans parameter leveraging {@link SimpleBean}.
     */
    @Parameter
    public Collection<SimpleBean> beans;

    /**
     * invalid javadoc reference {@link org.apache.maven.artifact.Artifact}.
     */
    @Parameter
    public Map<String,Boolean> invalidReference;

    @Parameter
    org.internal.PrivateBean privateBean;

    public void execute()
    {
        // intentional do nothing
    }

}
