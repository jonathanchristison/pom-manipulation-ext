package org.commonjava.maven.ext.manip.impl;

import static org.commonjava.maven.ext.manip.util.IdUtils.ga;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.maven.model.Build;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.commonjava.maven.ext.manip.ManipulationException;
import org.commonjava.maven.ext.manip.model.Project;
import org.commonjava.maven.ext.manip.state.ManipulationSession;
import org.commonjava.maven.ext.manip.state.ProjectSourcesInjectingState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Simple manipulator that detects the presence of the <a href="https://github.com/jdcasey/project-sources-maven-plugin">project-sources-maven-plugin</a>,
 * and injects it into the base build if it's not present. This plugin will simply create a source archive for the project sources AFTER this extension
 * has run, but BEFORE any sources are altered or generated by the normal build process. Configuration consists of a couple of properties, documented
 * in {@link ProjectSourcesInjectingState}.
 */
@Component( role = Manipulator.class, hint = "project-sources" )
public class ProjectSourcesInjectingManipulator
    implements Manipulator
{

    private static final String PROJECT_SOURCES_GID = "org.commonjava.maven.plugins";

    private static final String PROJECT_SOURCES_AID = "project-sources-maven-plugin";

    private static final String PROJECT_SOURCES_COORD = ga( PROJECT_SOURCES_GID, PROJECT_SOURCES_AID );

    private static final String BMMP_GID = "com.redhat.rcm.maven.plugin";

    private static final String BMMP_AID = "buildmetadata-maven-plugin";

    private static final String BMMP_COORD = ga( BMMP_GID, BMMP_AID );

    private static final String BMMP_GOAL = "provide-buildmetadata";

    private static final String BMMP_EXEC_ID = "build-metadata";

    private static final String PROJECT_SOURCES_GOAL = "archive";

    private static final String PROJECT_SOURCES_EXEC_ID = "project-sources-archive";

    private static final String VALIDATE_PHASE = "validate";

    private static final String INITIALIZE_PHASE = "initialize";

    private final Logger logger = LoggerFactory.getLogger( getClass() );

    @Override
    public void init( final ManipulationSession session )
        throws ManipulationException
    {
        session.setState( new ProjectSourcesInjectingState( session.getUserProperties() ) );
    }

    @Override
    public void scan( final List<Project> projects, final ManipulationSession session )
        throws ManipulationException
    {
    }

    /**
     * If enabled, grab the execution root pom (which will be the topmost POM in terms of directory structure). Check for the
     * presence of the project-sources-maven-plugin in the base build (/project/build/plugins/). Inject a new plugin execution for creating project
     * sources if this plugin has not already been declared in the base build section.
     */
    @Override
    public Set<Project> applyChanges( final List<Project> projects, final ManipulationSession session )
        throws ManipulationException
    {
        final ProjectSourcesInjectingState state = session.getState( ProjectSourcesInjectingState.class );

        // This manipulator will only run if its enabled *and* at least one other manipulator is enabled.
        if ( state.isEnabled() &&
             session.anyStateEnabled(Collections.singletonList( ProjectSourcesInjectingState.class ) ) )
        {
            for ( final Project project : projects )
            {
                if ( project.getPom().equals( session.getExecutionRoot() ))
                {
                    logger.info( "Examining {} to apply sources/metadata plugins.", project );

                    final Model model = project.getModel();
                    Build build = model.getBuild();
                    if ( build == null )
                    {
                        build = new Build();
                        model.setBuild( build );
                    }

                    boolean changed = false;
                    final Map<String, Plugin> pluginMap = build.getPluginsAsMap();
                    if ( !pluginMap.containsKey( PROJECT_SOURCES_COORD ) )
                    {
                        final PluginExecution execution = new PluginExecution();
                        execution.setId( PROJECT_SOURCES_EXEC_ID );
                        execution.setPhase( INITIALIZE_PHASE );
                        execution.setGoals( Collections.singletonList( PROJECT_SOURCES_GOAL ) );

                        final Plugin plugin = new Plugin();
                        plugin.setGroupId( PROJECT_SOURCES_GID );
                        plugin.setArtifactId( PROJECT_SOURCES_AID );
                        plugin.setVersion( state.getProjectSourcesPluginVersion() );
                        plugin.addExecution( execution );

                        build.addPlugin( plugin );

                        changed = true;
                    }

                    if ( !pluginMap.containsKey( BMMP_COORD ) )
                    {
                        final PluginExecution execution = new PluginExecution();
                        execution.setId( BMMP_EXEC_ID );
                        execution.setPhase( VALIDATE_PHASE );
                        execution.setGoals( Collections.singletonList( BMMP_GOAL ) );

                        final Xpp3Dom xml = new Xpp3Dom( "configuration" );

                        final Map<String, Object> config = new HashMap<String, Object>();
                        config.put( "createPropertiesReport", true );
                        config.put( "createXmlReport", false );
                        config.put( "hideCommandLineInfo", false );
                        config.put( "hideMavenOptsInfo", false );
                        config.put( "hideJavaOptsInfo", false );
                        config.put( "activateOutputFileMapping", false );
                        config.put( "propertiesOutputFile", "${basedir}/build.properties" );
                        config.put( "addJavaRuntimeInfo", true );
                        config.put( "addMavenExecutionInfo", true );
                        config.put( "addLocallyModifiedTagToFullVersion", false );
                        config.put( "addToGeneratedSources", false );
                        config.put( "validateCheckout", false );
                        config.put( "forceNewProperties", true );
                        config.put( "skipModules", true );

                        for ( final Map.Entry<String, Object> entry : config.entrySet() )
                        {
                            final Xpp3Dom child = new Xpp3Dom( entry.getKey() );
                            if ( entry.getValue() != null )
                            {
                                child.setValue( entry.getValue().toString() );
                            }

                            xml.addChild( child );
                        }

                        execution.setConfiguration( xml );

                        final Plugin plugin = new Plugin();
                        plugin.setGroupId( BMMP_GID );
                        plugin.setArtifactId( BMMP_AID );
                        plugin.setVersion( state.getBuildMetadataPluginVersion() );
                        plugin.addExecution( execution );
                        plugin.setInherited( false );

                        build.addPlugin( plugin );

                        changed = true;
                    }

                    if ( changed )
                    {
                        return Collections.singleton( project );
                    }
                }
            }
        }

        return Collections.emptySet();
    }

}
