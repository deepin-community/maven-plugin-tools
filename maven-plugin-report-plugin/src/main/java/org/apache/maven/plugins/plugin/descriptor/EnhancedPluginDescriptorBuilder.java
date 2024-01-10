package org.apache.maven.plugins.plugin.descriptor;

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

import org.apache.maven.plugin.descriptor.MojoDescriptor;
import org.apache.maven.plugin.descriptor.Parameter;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugin.descriptor.PluginDescriptorBuilder;
import org.apache.maven.rtinfo.RuntimeInformation;
import org.apache.maven.tools.plugin.EnhancedParameterWrapper;
import org.codehaus.plexus.configuration.PlexusConfiguration;
import org.codehaus.plexus.configuration.PlexusConfigurationException;
import org.apache.maven.plugin.plugin.report.PluginReport;

import java.net.URI;

/**
 * Reads enhanced plugin.xml files as generated by
 * {@link org.apache.maven.tools.plugin.generator.PluginDescriptorFilesGenerator} and
 * used by {@link PluginReport}.
 * Populates the slightly extended {@link Parameter} object {@link EnhancedParameterWrapper}.
 */
public class EnhancedPluginDescriptorBuilder extends PluginDescriptorBuilder
{
    private final boolean requireAddingMissingParameterSinceField;
    
    public EnhancedPluginDescriptorBuilder( RuntimeInformation rtInfo )
    {
        this( rtInfo.isMavenVersion( "[,3.3.9]" ) );
    }

    EnhancedPluginDescriptorBuilder( boolean requireAddingMissingParameterSinceField )
    {
        this.requireAddingMissingParameterSinceField = requireAddingMissingParameterSinceField;
    }

    @Override
    public MojoDescriptor buildComponentDescriptor( PlexusConfiguration c, PluginDescriptor pluginDescriptor )
        throws PlexusConfigurationException
    {
        MojoDescriptor mojoDescriptor = super.buildComponentDescriptor( c, pluginDescriptor );
        
        // ----------------------------------------------------------------------
        // Parameters
        // ----------------------------------------------------------------------

        PlexusConfiguration[] parameterConfigurations = c.getChild( "parameters" ).getChildren( "parameter" );

        for ( PlexusConfiguration d : parameterConfigurations )
        {
            String parameterName = d.getChild( "name" ).getValue();
            // don't call getParameterMap() to not populate 
            Parameter pd = mojoDescriptor.getParameterMap().get( parameterName );
            if ( requireAddingMissingParameterSinceField )
            {
                addMissingParameterSinceField( pd, d );
            }
            PlexusConfiguration configTypeJavadocUrl = d.getChild( "typeJavadocUrl", false );
            if ( configTypeJavadocUrl != null )
            {
                String parameterTypeJavadocUrl = configTypeJavadocUrl.getValue();
                EnhancedParameterWrapper enhancedParameter = new EnhancedParameterWrapper( pd );
                enhancedParameter.setTypeJavadocUrl( URI.create( parameterTypeJavadocUrl ) );
                mojoDescriptor.getParameters().set( mojoDescriptor.getParameters().indexOf( pd ), enhancedParameter );
                mojoDescriptor.getParameterMap().put( parameterName, enhancedParameter );
            }
        }
        return mojoDescriptor;
    }

    /**
     * Reads the plugin descriptor and adds the fix for <a href="https://issues.apache.org/jira/browse/MNG-6109">
     * MNG-6109</a> when using Maven-3.3.9 and before.
     * Method can be removed once Maven 3.5.0 is the prerequisite for this plugin.
     * @throws PlexusConfigurationException 
     * 
     * @since 3.5.1
     * @see <a href="https://issues.apache.org/jira/browse/MNG-6109">MNG-6109</a>
     * @see <a href="https://issues.apache.org/jira/browse/MPLUGIN-319">MPLUGIN-319</a>
     */
     void addMissingParameterSinceField( Parameter pd, PlexusConfiguration d ) throws PlexusConfigurationException
     {
         String parameterSince = d.getChild( "since" ).getValue();
         pd.setSince( parameterSince );
     }
}
