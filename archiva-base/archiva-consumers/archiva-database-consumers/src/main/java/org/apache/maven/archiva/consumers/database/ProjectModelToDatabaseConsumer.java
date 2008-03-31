package org.apache.maven.archiva.consumers.database;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import org.apache.commons.lang.StringUtils;
import org.apache.maven.archiva.common.utils.VersionUtil;
import org.apache.maven.archiva.consumers.AbstractMonitoredConsumer;
import org.apache.maven.archiva.consumers.ConsumerException;
import org.apache.maven.archiva.consumers.DatabaseUnprocessedArtifactConsumer;
import org.apache.maven.archiva.database.ArchivaDAO;
import org.apache.maven.archiva.database.ArchivaDatabaseException;
import org.apache.maven.archiva.database.ObjectNotFoundException;
import org.apache.maven.archiva.model.ArchivaArtifact;
import org.apache.maven.archiva.model.ArchivaProjectModel;
import org.apache.maven.archiva.model.Keys;
import org.apache.maven.archiva.model.RepositoryProblem;
import org.apache.maven.archiva.reporting.artifact.CorruptArtifactReport;
import org.apache.maven.archiva.repository.ManagedRepositoryContent;
import org.apache.maven.archiva.repository.RepositoryContentFactory;
import org.apache.maven.archiva.repository.RepositoryException;
import org.apache.maven.archiva.repository.content.ManagedLegacyRepositoryContent;
import org.apache.maven.archiva.repository.project.ProjectModelException;
import org.apache.maven.archiva.repository.project.ProjectModelFilter;
import org.apache.maven.archiva.repository.project.ProjectModelReader;
import org.apache.maven.archiva.repository.project.filters.EffectiveProjectModelFilter;

import org.codehaus.plexus.cache.Cache;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * ProjectModelToDatabaseConsumer
 *
 * @author <a href="mailto:joakime@apache.org">Joakim Erdfelt</a>
 * @version $Id$
 * @plexus.component role="org.apache.maven.archiva.consumers.DatabaseUnprocessedArtifactConsumer"
 * role-hint="update-db-project"
 * instantiation-strategy="per-lookup"
 */
public class ProjectModelToDatabaseConsumer
    extends AbstractMonitoredConsumer
    implements DatabaseUnprocessedArtifactConsumer
{
    /**
     * @plexus.configuration default-value="update-db-project"
     */
    private String id;

    /**
     * @plexus.configuration default-value="Update database with project model information."
     */
    private String description;

    /**
     * @plexus.requirement role-hint="jdo"
     */
    private ArchivaDAO dao;

    /**
     * @plexus.requirement
     */
    private RepositoryContentFactory repositoryFactory;

    /**
     * @plexus.requirement role-hint="model400"
     */
    private ProjectModelReader project400Reader;

    /**
     * @plexus.requirement role-hint="model300"
     */
    private ProjectModelReader project300Reader;

    /**
     * @plexus.requirement role-hint="expression"
     */
    private ProjectModelFilter expressionModelFilter;

    /**
     * @plexus.requirement role="org.apache.maven.archiva.repository.project.ProjectModelFilter"
     * role-hint="effective"
     */
    private EffectiveProjectModelFilter effectiveModelFilter;

    private List<String> includes;

    /**
     * @plexus.requirement role-hint="effective-project-cache"
     */
    private Cache effectiveProjectCache;

    public ProjectModelToDatabaseConsumer()
    {
        includes = new ArrayList<String>();
        includes.add( "pom" );
    }

    public void beginScan()
    {
        /* nothing to do here */
    }

    public void completeScan()
    {
        /* nothing to do here */
    }

    public List<String> getIncludedTypes()
    {
        return includes;
    }

    public void processArchivaArtifact( ArchivaArtifact artifact )
        throws ConsumerException
    {
        if ( !StringUtils.equals( "pom", artifact.getType() ) )
        {
            // Not a pom.  Skip it.
            return;
        }
        
        ArchivaProjectModel model = null;
        
        // remove old project model if it already exists in the database
        if ( ( model =
            getProjectModelFromDatabase( artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion() ) ) != null )
        {
            removeOldProjectModel( model );
            model = null;
        }

        ManagedRepositoryContent repo = getRepository( artifact );
        File artifactFile = repo.toFile( artifact );
        ProjectModelReader reader = project400Reader;

        if ( repo instanceof ManagedLegacyRepositoryContent )
        {
            reader = project300Reader;
        }

        try
        {
            model = reader.read( artifactFile );

            model.setOrigin( "filesystem" );

            // The version should be updated to the artifact/filename version if it is a unique snapshot
            if ( VersionUtil.isUniqueSnapshot( artifact.getVersion() ) )
            {
                model.setVersion( artifact.getVersion() );
            }

            // Filter the model
            model = expressionModelFilter.filter( model );

            // Resolve the project model
            model = effectiveModelFilter.filter( model );

            if ( isValidModel( model, repo, artifact ) )
            {
                getLogger().debug( "Adding project model to database - " + Keys.toKey( model ) );                

                dao.getProjectModelDAO().saveProjectModel( model );
            }
            else
            {
                getLogger().warn(
                    "Invalid or corrupt pom. Project model not added to database - " + Keys.toKey( model ) );
            }

        }
        catch ( ProjectModelException e )
        {
            getLogger().warn( "Unable to read project model " + artifactFile + " : " + e.getMessage(), e );

            addProblem( artifact, "Unable to read project model " + artifactFile + " : " + e.getMessage() );
        }
        catch ( ArchivaDatabaseException e )
        {
            getLogger().warn( "Unable to save project model " + artifactFile + " to the database : " + e.getMessage(),
                              e );
        }
        catch ( Throwable t )
        {
            // Catch the other errors in the process to allow the rest of the process to complete.
            getLogger().error( "Unable to process model " + artifactFile + " due to : " + t.getClass().getName() +
                " : " + t.getMessage(), t );
        }
    }

    private ArchivaProjectModel getProjectModelFromDatabase( String groupId, String artifactId, String version )
    {
        try
        {
            ArchivaProjectModel model = dao.getProjectModelDAO().getProjectModel( groupId, artifactId, version );
            return model;
        }
        catch ( ObjectNotFoundException e )
        {
            return null;
        }
        catch ( ArchivaDatabaseException e )
        {
            return null;
        }
    }

    private ManagedRepositoryContent getRepository( ArchivaArtifact artifact )
        throws ConsumerException
    {
        String repoId = artifact.getModel().getRepositoryId();
        try
        {
            return repositoryFactory.getManagedRepositoryContent( repoId );
        }
        catch ( RepositoryException e )
        {
            throw new ConsumerException( "Unable to process project model: " + e.getMessage(), e );
        }
    }

    public String getDescription()
    {
        return description;
    }

    public String getId()
    {
        return id;
    }

    public boolean isPermanent()
    {
        // Tells the configuration that this consumer cannot be disabled.
        return true;
    }

    private boolean isValidModel( ArchivaProjectModel model, ManagedRepositoryContent repo, ArchivaArtifact artifact )
        throws ConsumerException
    {
        File artifactFile = repo.toFile( artifact );

        if ( !artifact.getArtifactId().equalsIgnoreCase( model.getArtifactId() ) )
        {
            StringBuffer emsg = new StringBuffer();
            emsg.append( "File " ).append( artifactFile.getName() );
            emsg.append( " has an invalid project model [" );
            appendModel( emsg, model );
            emsg.append( "]: The model artifactId [" ).append( model.getArtifactId() );
            emsg.append( "] does not match the artifactId portion of the filename: " ).append( artifact.getArtifactId() );
	    
            getLogger().warn(emsg.toString() );
            addProblem( artifact, emsg.toString() );

            return false;
        }

        if ( !artifact.getVersion().equalsIgnoreCase( model.getVersion() ) &&
            !VersionUtil.getBaseVersion( artifact.getVersion() ).equalsIgnoreCase( model.getVersion() ) )
        {
            StringBuffer emsg = new StringBuffer();
            emsg.append( "File " ).append( artifactFile.getName() );
            emsg.append( " has an invalid project model [" );
            appendModel( emsg, model );
            emsg.append( "]; The model version [" ).append( model.getVersion() );
            emsg.append( "] does not match the version portion of the filename: " ).append( artifact.getVersion() );
	    
            getLogger().warn(emsg.toString() );
            addProblem( artifact, emsg.toString() );

            return false;
        }

        return true;
    }

    private void appendModel( StringBuffer buf, ArchivaProjectModel model )
    {
        buf.append( "groupId:" ).append( model.getGroupId() );
        buf.append( "|artifactId:" ).append( model.getArtifactId() );
        buf.append( "|version:" ).append( model.getVersion() );
        buf.append( "|packaging:" ).append( model.getPackaging() );
    }

    private void addProblem( ArchivaArtifact artifact, String msg )
        throws ConsumerException
    {
        ManagedRepositoryContent repo = getRepository( artifact );

        RepositoryProblem problem = new RepositoryProblem();
        problem.setRepositoryId( artifact.getModel().getRepositoryId() );
        problem.setPath( repo.toPath( artifact ) );
        problem.setGroupId( artifact.getGroupId() );
        problem.setArtifactId( artifact.getArtifactId() );
        problem.setVersion( artifact.getVersion() );
        problem.setType( CorruptArtifactReport.PROBLEM_TYPE_CORRUPT_ARTIFACT );
        problem.setOrigin( getId() );
        problem.setMessage( msg );

        try
        {
            dao.getRepositoryProblemDAO().saveRepositoryProblem( problem );
        }
        catch ( ArchivaDatabaseException e )
        {
            String emsg = "Unable to save problem with artifact location to DB: " + e.getMessage();
            getLogger().warn( emsg, e );
            throw new ConsumerException( emsg, e );
        }
    }

    private String toProjectKey( ArchivaProjectModel project )
    {
        StringBuilder key = new StringBuilder();

        key.append( project.getGroupId() ).append( ":" );
        key.append( project.getArtifactId() ).append( ":" );
        key.append( project.getVersion() );

        return key.toString();
    }

    private void removeOldProjectModel( ArchivaProjectModel model )
    {
        try
        {
            dao.getProjectModelDAO().deleteProjectModel( model );
        }
        catch ( ArchivaDatabaseException ae )
        {
            getLogger().error( "Unable to delete existing project model." );
        }

        // Force removal of project model from effective cache
        String projectKey = toProjectKey( model );
        synchronized ( effectiveProjectCache )
        {
            if ( effectiveProjectCache.hasKey( projectKey ) )
            {
                effectiveProjectCache.remove( projectKey );
            }
        }
    }
    
}
