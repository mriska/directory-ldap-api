/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *  
 *    http://www.apache.org/licenses/LICENSE-2.0
 *  
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License. 
 *  
 */
package org.apache.directory.shared.schema;


import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.naming.NamingException;

import org.apache.directory.shared.ldap.NotImplementedException;
import org.apache.directory.shared.ldap.constants.MetaSchemaConstants;
import org.apache.directory.shared.ldap.constants.SchemaConstants;
import org.apache.directory.shared.ldap.entry.Entry;
import org.apache.directory.shared.ldap.exception.LdapSchemaViolationException;
import org.apache.directory.shared.ldap.message.ResultCodeEnum;
import org.apache.directory.shared.ldap.name.LdapDN;
import org.apache.directory.shared.ldap.schema.AttributeType;
import org.apache.directory.shared.ldap.schema.EntityFactory;
import org.apache.directory.shared.ldap.schema.LdapComparator;
import org.apache.directory.shared.ldap.schema.LdapSyntax;
import org.apache.directory.shared.ldap.schema.MatchingRule;
import org.apache.directory.shared.ldap.schema.Normalizer;
import org.apache.directory.shared.ldap.schema.ObjectClass;
import org.apache.directory.shared.ldap.schema.SchemaManager;
import org.apache.directory.shared.ldap.schema.SchemaObject;
import org.apache.directory.shared.ldap.schema.SchemaObjectWrapper;
import org.apache.directory.shared.ldap.schema.SyntaxChecker;
import org.apache.directory.shared.ldap.schema.normalizers.OidNormalizer;
import org.apache.directory.shared.ldap.schema.registries.AttributeTypeRegistry;
import org.apache.directory.shared.ldap.schema.registries.ComparatorRegistry;
import org.apache.directory.shared.ldap.schema.registries.DITContentRuleRegistry;
import org.apache.directory.shared.ldap.schema.registries.DITStructureRuleRegistry;
import org.apache.directory.shared.ldap.schema.registries.ImmutableAttributeTypeRegistry;
import org.apache.directory.shared.ldap.schema.registries.ImmutableComparatorRegistry;
import org.apache.directory.shared.ldap.schema.registries.ImmutableDITContentRuleRegistry;
import org.apache.directory.shared.ldap.schema.registries.ImmutableDITStructureRuleRegistry;
import org.apache.directory.shared.ldap.schema.registries.ImmutableLdapSyntaxRegistry;
import org.apache.directory.shared.ldap.schema.registries.ImmutableMatchingRuleRegistry;
import org.apache.directory.shared.ldap.schema.registries.ImmutableMatchingRuleUseRegistry;
import org.apache.directory.shared.ldap.schema.registries.ImmutableNameFormRegistry;
import org.apache.directory.shared.ldap.schema.registries.ImmutableNormalizerRegistry;
import org.apache.directory.shared.ldap.schema.registries.ImmutableObjectClassRegistry;
import org.apache.directory.shared.ldap.schema.registries.ImmutableSyntaxCheckerRegistry;
import org.apache.directory.shared.ldap.schema.registries.LdapSyntaxRegistry;
import org.apache.directory.shared.ldap.schema.registries.MatchingRuleRegistry;
import org.apache.directory.shared.ldap.schema.registries.MatchingRuleUseRegistry;
import org.apache.directory.shared.ldap.schema.registries.NameFormRegistry;
import org.apache.directory.shared.ldap.schema.registries.NormalizerRegistry;
import org.apache.directory.shared.ldap.schema.registries.ObjectClassRegistry;
import org.apache.directory.shared.ldap.schema.registries.OidRegistry;
import org.apache.directory.shared.ldap.schema.registries.Registries;
import org.apache.directory.shared.ldap.schema.registries.Schema;
import org.apache.directory.shared.ldap.schema.registries.SchemaLoader;
import org.apache.directory.shared.ldap.schema.registries.SyntaxCheckerRegistry;
import org.apache.directory.shared.ldap.util.StringTools;
import org.apache.directory.shared.schema.loader.ldif.SchemaEntityFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * The SchemaManager class : it handles all the schema operations (addition, removal,
 * modification).
 *
 * @author <a href="mailto:dev@directory.apache.org">Apache Directory Project</a>
 * @version $Rev$, $Date$
 */
public class DefaultSchemaManager implements SchemaManager
{
    /** static class logger */
    private static final Logger   LOG       = LoggerFactory.getLogger( DefaultSchemaManager.class );

    /** The NamingContext this SchemaManager is associated with */
    private LdapDN                namingContext;

    /** The global registries for this namingContext */
    private volatile Registries   registries;

    /** The list of errors produced when loading some schema elements */
    private List<Throwable>       errors;

    /** The Schema schemaLoader used by this SchemaManager */
    private SchemaLoader          schemaLoader;

    /** the factory that generates respective SchemaObjects from LDIF entries */
    protected final EntityFactory factory;

    /** the normalized name for the schema modification attributes */
    private LdapDN                schemaModificationAttributesDN;

    /** A flag indicating that the SchemaManager is relaxed or not */
    private boolean               isRelaxed = STRICT;

    /** Two flags for RELAXED and STRUCT */
    public static final boolean   STRICT    = false;
    public static final boolean   RELAXED   = true;


    /**
     * Creates a new instance of DefaultSchemaManager with the default schema schemaLoader
     *
     * @param schemaLoader
     */
    public DefaultSchemaManager( SchemaLoader loader ) throws Exception
    {
        // Default to the the root (one schemaManager for all the entries
        namingContext = LdapDN.EMPTY_LDAPDN;
        this.schemaLoader = loader;
        errors = null;
        registries = new Registries( this );
        factory = new SchemaEntityFactory();
        isRelaxed = STRICT;
    }


    /**
     * Creates a new instance of DefaultSchemaManager, for a specific
     * naming context
     *
     * @param namingContext The associated NamingContext
     */
    public DefaultSchemaManager( SchemaLoader loader, LdapDN namingContext ) throws Exception
    {
        this.namingContext = namingContext;
        this.schemaLoader = loader;
        errors = null;
        registries = new Registries( this );
        factory = new SchemaEntityFactory();
        isRelaxed = STRICT;
    }


    //-----------------------------------------------------------------------
    // Helper methods
    //-----------------------------------------------------------------------
    /**
     * Clone the registries before doing any modification on it. Relax it
     * too so that we can update it. 
     */
    private Registries cloneRegistries() throws Exception
    {
        // Relax the controls at first
        errors = new ArrayList<Throwable>();

        // Clone the Registries
        Registries clonedRegistries = registries.clone();

        // And update references. We may have errors, that may be fixed
        // by the new loaded schemas.
        errors = clonedRegistries.checkRefInteg();

        // Now, relax the cloned Registries if there is no error
        clonedRegistries.setRelaxed();

        return clonedRegistries;
    }


    /**
     * Transform a String[] array of schema to a Schema[]
     */
    private Schema[] toArray( String... schemas ) throws Exception
    {
        Schema[] schemaArray = new Schema[schemas.length];
        int n = 0;

        for ( String schemaName : schemas )
        {
            schemaArray[n++] = schemaLoader.getSchema( schemaName );
        }

        return schemaArray;
    }


    private void addSchemaObjects( Schema schema, Registries registries ) throws Exception
    {
        addComparators( schema, registries );
        addNormalizers( schema, registries );
        addSyntaxCheckers( schema, registries );
        addSyntaxes( schema, registries );
        addMatchingRules( schema, registries );
        addAttributeTypes( schema, registries );
        addObjectClasses( schema, registries );
        addMatchingRuleUses( schema, registries );
        addDitContentRules( schema, registries );
        addNameForms( schema, registries );
        addDitStructureRules( schema, registries );

        // TODO Add some listener handling at this point
        //notifyListenerOrRegistries( schema, registries );
    }


    //-----------------------------------------------------------------------
    // API methods
    //-----------------------------------------------------------------------
    /***
     * {@inheritDoc}
     */
    public boolean swapRegistries( Registries targetRegistries ) throws NamingException
    {
        // Check the resulting registries
        errors = targetRegistries.checkRefInteg();

        // if we have no more error, we can swap the registries
        if ( errors.size() == 0 )
        {
            // Switch back to strict if needed
            if ( registries.isStrict() )
            {
                targetRegistries.setStrict();
            }

            Registries oldRegistries = registries;
            registries = targetRegistries;

            // Delete the old registries to avoid memory leaks
            oldRegistries.clear();

            return true;
        }
        else
        {
            // We can't use this new registries.
            return false;
        }
    }


    /**
     * {@inheritDoc}
     */
    public boolean disable( Schema... schemas )
    {
        // TODO Auto-generated method stub
        return false;
    }


    /**
     * {@inheritDoc}
     */
    public boolean disable( String... schemas )
    {
        // TODO Auto-generated method stub
        return false;
    }


    /**
     * {@inheritDoc}
     */
    public boolean disabledRelaxed( Schema... schemas )
    {
        // TODO Auto-generated method stub
        return false;
    }


    /**
     * {@inheritDoc}
     */
    public boolean disabledRelaxed( String... schemas )
    {
        // TODO Auto-generated method stub
        return false;
    }


    /**
     * {@inheritDoc}
     */
    public List<Schema> getDisabled()
    {
        List<Schema> disabled = new ArrayList<Schema>();

        for ( Schema schema : registries.getLoadedSchemas().values() )
        {
            if ( schema.isDisabled() )
            {
                disabled.add( schema );
            }
        }

        return disabled;
    }


    /**
     * {@inheritDoc}
     */
    public boolean enable( Schema... schemas ) throws Exception
    {
        boolean enabled = false;

        // Reset the errors if not null
        if ( errors != null )
        {
            errors.clear();
        }

        // Work on a cloned and relaxed registries
        Registries clonedRegistries = cloneRegistries();
        clonedRegistries.setRelaxed();

        for ( Schema schema : schemas )
        {
            schema.enable();
            load( clonedRegistries, schema );
        }

        // Build the cross references
        errors = clonedRegistries.buildReferences();

        // Destroy the clonedRegistry
        clonedRegistries.clear();

        if ( errors.isEmpty() )
        {
            // Ok no errors. Check the registries now
            errors = clonedRegistries.checkRefInteg();

            if ( errors.isEmpty() )
            {
                // We are golden : let's apply the schemas in the real registries
                for ( Schema schema : schemas )
                {
                    schema.enable();
                    load( registries, schema );
                }

                // Build the cross references
                errors = registries.buildReferences();
                registries.setStrict();

                enabled = true;
            }
        }

        // clear the cloned registries
        clonedRegistries.clear();

        return enabled;
    }


    /**
     * {@inheritDoc}
     */
    public boolean enable( String... schemas ) throws Exception
    {
        return enable( toArray( schemas ) );
    }


    /**
     * {@inheritDoc}
     */
    public boolean enableRelaxed( Schema... schemas )
    {
        // TODO Auto-generated method stub
        return false;
    }


    /**
     * {@inheritDoc}
     */
    public boolean enableRelaxed( String... schemas )
    {
        // TODO Auto-generated method stub
        return false;
    }


    /**
     * {@inheritDoc}
     */
    public List<Schema> getEnabled()
    {
        List<Schema> enabled = new ArrayList<Schema>();

        for ( Schema schema : registries.getLoadedSchemas().values() )
        {
            if ( schema.isEnabled() )
            {
                enabled.add( schema );
            }
        }

        return enabled;
    }


    /**
     * {@inheritDoc}
     */
    public List<Throwable> getErrors()
    {
        return errors;
    }


    /**
     * {@inheritDoc}
     */
    public Registries getRegistries()
    {
        return registries;
    }


    /**
     * {@inheritDoc}
     */
    public boolean isDisabledAccepted()
    {
        // TODO Auto-generated method stub
        return false;
    }


    /**
     * {@inheritDoc}
     */
    public boolean load( Schema... schemas ) throws Exception
    {
        boolean loaded = false;

        // Reset the errors if not null
        if ( errors != null )
        {
            errors.clear();
        }

        // Work on a cloned and relaxed registries
        Registries clonedRegistries = cloneRegistries();
        clonedRegistries.setRelaxed();

        // Load the schemas
        for ( Schema schema : schemas )
        {
            load( clonedRegistries, schema );
        }

        // Build the cross references
        errors = clonedRegistries.buildReferences();

        if ( errors.isEmpty() )
        {
            // Ok no errors. Check the registries now
            errors = clonedRegistries.checkRefInteg();

            if ( errors.isEmpty() )
            {
                // We are golden : let's apply the schema in the real registries
                registries.setRelaxed();

                // Load the schemas
                for ( Schema schema : schemas )
                {
                    load( registries, schema );
                }

                // Build the cross references
                errors = registries.buildReferences();
                registries.setStrict();

                loaded = true;
            }
        }

        // clear the cloned registries
        clonedRegistries.clear();

        return loaded;
    }


    /**
     * {@inheritDoc}
     */
    public boolean load( String... schemas ) throws Exception
    {
        return load( toArray( schemas ) );
    }


    /**
     * Load the schema in the registries. We will load everything accordingly to the two flags :
     * - isRelaxed
     * - disabledAccepted
     *
     * @param registries
     * @param schemas
     * @return
     * @throws Exception
     */
    private boolean load( Registries registries, Schema schema ) throws Exception
    {
        if ( schema == null )
        {
            LOG.info( "The schema is null" );
            return false;
        }

        // First avoid loading twice the same schema
        if ( registries.isSchemaLoaded( schema.getSchemaName() ) )
        {
            return true;
        }

        if ( schema.isDisabled() )
        {
            if ( registries.isDisabledAccepted() )
            {
                LOG.info( "Loading {} schema: \n{}", schema.getSchemaName(), schema );

                registries.schemaLoaded( schema );

                addSchemaObjects( schema, registries );
            }
            else
            {
                return false;
            }
        }
        else
        {
            LOG.info( "Loading {} schema: \n{}", schema.getSchemaName(), schema );

            registries.schemaLoaded( schema );
            addSchemaObjects( schema, registries );
        }

        return true;
    }


    /**
     * Add all the Schema's AttributeTypes
     */
    private void addAttributeTypes( Schema schema, Registries registries ) throws Exception
    {
        for ( Entry entry : schemaLoader.loadAttributeTypes( schema ) )
        {
            AttributeType attributeType = factory.getAttributeType( this, entry, registries, schema.getSchemaName() );

            addSchemaObject( registries, attributeType, schema );
        }
    }


    /**
     * Add all the Schema's comparators
     */
    private void addComparators( Schema schema, Registries registries ) throws Exception
    {
        for ( Entry entry : schemaLoader.loadComparators( schema ) )
        {
            LdapComparator<?> comparator = factory.getLdapComparator( this, entry, registries, schema.getSchemaName() );

            addSchemaObject( registries, comparator, schema );
        }
    }


    /**
     * Add all the Schema's DitContentRules
     */
    private void addDitContentRules( Schema schema, Registries registries ) throws Exception
    {
        for ( Entry entry : schemaLoader.loadDitContentRules( schema ) )
        {
            throw new NotImplementedException( "Need to implement factory " + "method for creating a DitContentRule" );
        }
    }


    /**
     * Add all the Schema's DitStructureRules
     */
    private void addDitStructureRules( Schema schema, Registries registries ) throws Exception
    {
        for ( Entry entry : schemaLoader.loadDitStructureRules( schema ) )
        {
            throw new NotImplementedException( "Need to implement factory " + "method for creating a DitStructureRule" );
        }
    }


    /**
     * Add all the Schema's MatchingRules
     */
    private void addMatchingRules( Schema schema, Registries registries ) throws Exception
    {
        for ( Entry entry : schemaLoader.loadMatchingRules( schema ) )
        {
            MatchingRule matchingRule = factory.getMatchingRule( this, entry, registries, schema.getSchemaName() );

            addSchemaObject( registries, matchingRule, schema );
        }
    }


    /**
     * Add all the Schema's MatchingRuleUses
     */
    private void addMatchingRuleUses( Schema schema, Registries registries ) throws Exception
    {
        for ( Entry entry : schemaLoader.loadMatchingRuleUses( schema ) )
        {
            throw new NotImplementedException( "Need to implement factory " + "method for creating a MatchingRuleUse" );
        }
    }


    /**
     * Add all the Schema's NameForms
     */
    private void addNameForms( Schema schema, Registries registries ) throws Exception
    {
        for ( Entry entry : schemaLoader.loadNameForms( schema ) )
        {
            throw new NotImplementedException( "Need to implement factory " + "method for creating a NameForm" );
        }
    }


    /**
     * Add all the Schema's Normalizers
     */
    private void addNormalizers( Schema schema, Registries registries ) throws Exception
    {
        for ( Entry entry : schemaLoader.loadNormalizers( schema ) )
        {
            Normalizer normalizer = factory.getNormalizer( this, entry, registries, schema.getSchemaName() );

            addSchemaObject( registries, normalizer, schema );
        }
    }


    /**
     * Add all the Schema's ObjectClasses
     */
    private void addObjectClasses( Schema schema, Registries registries ) throws Exception
    {
        for ( Entry entry : schemaLoader.loadObjectClasses( schema ) )
        {
            ObjectClass objectClass = factory.getObjectClass( this, entry, registries, schema.getSchemaName() );

            addSchemaObject( registries, objectClass, schema );
        }
    }


    /**
     * Add all the Schema's Syntaxes
     */
    private void addSyntaxes( Schema schema, Registries registries ) throws Exception
    {
        for ( Entry entry : schemaLoader.loadSyntaxes( schema ) )
        {
            LdapSyntax syntax = factory.getSyntax( this, entry, registries, schema.getSchemaName() );

            addSchemaObject( registries, syntax, schema );
        }
    }


    /**Add
     * Register all the Schema's SyntaxCheckers
     */
    private void addSyntaxCheckers( Schema schema, Registries registries ) throws Exception
    {
        for ( Entry entry : schemaLoader.loadSyntaxCheckers( schema ) )
        {
            SyntaxChecker syntaxChecker = factory.getSyntaxChecker( this, entry, registries, schema.getSchemaName() );

            addSchemaObject( registries, syntaxChecker, schema );
        }
    }


    /**
     * Add the schemaObject into the registries. 
     *
     * @param registries The Registries
     * @param schemaObject The SchemaObject containing the SchemaObject description
     * @param schema The associated schema
     * @return the created schemaObject instance
     * @throws Exception If the registering failed
     */
    private SchemaObject addSchemaObject( Registries registries, SchemaObject schemaObject, Schema schema )
        throws Exception
    {
        if ( registries.isRelaxed() )
        {
            if ( registries.isDisabledAccepted() || ( schema.isEnabled() && schemaObject.isEnabled() ) )
            {
                registries.add( errors, schemaObject );
            }
            else
            {
                errors.add( new Throwable() );
            }
        }
        else
        {
            if ( schema.isEnabled() && schemaObject.isEnabled() )
            {
                registries.add( errors, schemaObject );
            }
            else
            {
                errors.add( new Throwable() );
            }
        }

        return schemaObject;
    }


    /**
     * {@inheritDoc}
     */
    public boolean loadAllEnabled() throws Exception
    {
        Schema[] schemas = schemaLoader.getAllEnabled().toArray( new Schema[0] );

        return loadWithDeps( schemas );
    }


    /**
     * {@inheritDoc}
     */
    public boolean loadAllEnabledRelaxed() throws Exception
    {
        // TODO Auto-generated method stub
        return false;
    }


    /**
     * {@inheritDoc}
     */
    public boolean loadDisabled( Schema... schemas ) throws Exception
    {
        // Work on a cloned and relaxed registries
        Registries clonedRegistries = cloneRegistries();

        // Accept the disabled schemas
        clonedRegistries.setDisabledAccepted( true );

        // Load the schemas
        for ( Schema schema : schemas )
        {
            // Enable the Schema object before loading it
            schema.enable();
            load( clonedRegistries, schema );
        }

        // Swap the registries if it is consistent
        return swapRegistries( clonedRegistries );
    }


    /**
     * {@inheritDoc}
     */
    public boolean loadDisabled( String... schemas ) throws Exception
    {
        return loadDisabled( toArray( schemas ) );
    }


    /**
     * {@inheritDoc}
     */
    public boolean loadRelaxed( Schema... schemas ) throws Exception
    {
        // TODO Auto-generated method stub
        return false;
    }


    /**
     * {@inheritDoc}
     */
    public boolean loadRelaxed( String... schemas ) throws Exception
    {
        // TODO Auto-generated method stub
        return false;
    }


    /**
     * {@inheritDoc}
     */
    public boolean loadWithDeps( Schema... schemas ) throws Exception
    {
        boolean loaded = false;

        // Reset the errors if not null
        if ( errors != null )
        {
            errors.clear();
        }

        // Work on a cloned and relaxed registries
        Registries clonedRegistries = cloneRegistries();
        clonedRegistries.setRelaxed();

        // Load the schemas
        for ( Schema schema : schemas )
        {
            loadDepsFirst( clonedRegistries, schema );
        }

        // Build the cross references
        errors = clonedRegistries.buildReferences();

        if ( errors.isEmpty() )
        {
            // Ok no errors. Check the registries now
            errors = clonedRegistries.checkRefInteg();

            if ( errors.isEmpty() )
            {
                // We are golden : let's apply the schema in the real registries
                registries.setRelaxed();

                // Load the schemas
                for ( Schema schema : schemas )
                {
                    loadDepsFirst( registries, schema );
                }

                // Build the cross references
                errors = registries.buildReferences();
                registries.setStrict();

                loaded = true;
            }
        }

        // clear the cloned registries
        clonedRegistries.clear();

        return loaded;
    }


    /**
     * {@inheritDoc}
     */
    public boolean loadWithDeps( String... schemas ) throws Exception
    {
        return loadWithDeps( toArray( schemas ) );
    }


    /**
     * Recursive method which loads schema's with their dependent schemas first
     * and tracks what schemas it has seen so the recursion does not go out of
     * control with dependency cycle detection.
     *
     * @param registries The Registries in which the schemas will be loaded
     * @param schema the current schema we are attempting to load
     * @throws Exception if there is a cycle detected and/or another
     * failure results while loading, producing and or registering schema objects
     */
    private final void loadDepsFirst( Registries registries, Schema schema ) throws Exception
    {
        if ( schema == null )
        {
            LOG.info( "The schema is null" );
            return;
        }

        if ( schema.isDisabled() && !registries.isDisabledAccepted() )
        {
            LOG.info( "The schema is disabled and the registries does not accepted disabled schema" );
            return;
        }

        String schemaName = schema.getSchemaName();

        if ( registries.isSchemaLoaded( schemaName ) )
        {
            LOG.info( "{} schema has already been loaded" + schema.getSchemaName() );
            return;
        }

        String[] deps = schema.getDependencies();

        // if no deps then load this guy and return
        if ( ( deps == null ) || ( deps.length == 0 ) )
        {
            load( registries, schema );

            return;
        }

        /*
         * We got deps and need to load them before this schema.  We go through
         * all deps loading them with their deps first if they have not been
         * loaded.
         */
        for ( String depName : deps )
        {
            if ( registries.isSchemaLoaded( schemaName ) )
            {
                // The schema is already loaded. Loop on the next schema
                continue;
            }
            else
            {
                // Call recursively this method
                Schema schemaDep = schemaLoader.getSchema( depName );
                loadDepsFirst( registries, schemaDep );
            }
        }

        // Now load the current schema
        load( registries, schema );
    }


    /**
     * {@inheritDoc}
     */
    public boolean loadWithDepsRelaxed( Schema... schemas ) throws Exception
    {
        // TODO Auto-generated method stub
        return false;
    }


    /**
     * {@inheritDoc}
     */
    public boolean loadWithDepsRelaxed( String... schemas ) throws Exception
    {
        // TODO Auto-generated method stub
        return false;
    }


    /**
     * {@inheritDoc}
     */
    public void setRegistries( Registries registries )
    {
        // TODO Auto-generated method stub

    }


    /**
     * {@inheritDoc}
     */
    public boolean unload( Schema... schemas )
    {
        // TODO Auto-generated method stub
        return false;
    }


    /**
     * {@inheritDoc}
     */
    public boolean unload( String... schemas )
    {
        // TODO Auto-generated method stub
        return false;
    }


    /**
     * {@inheritDoc}
     */
    public boolean verify( Schema... schemas ) throws Exception
    {
        // Work on a cloned registries
        Registries clonedRegistries = cloneRegistries();

        // Loop on all the schemas 
        for ( Schema schema : schemas )
        {
            try
            {
                // Inject the schema
                boolean loaded = load( clonedRegistries, schema );

                if ( !loaded )
                {
                    // We got an error : exit
                    clonedRegistries.clear();
                    return false;
                }

                // Now, check the registries
                List<Throwable> errors = clonedRegistries.checkRefInteg();

                if ( errors.size() != 0 )
                {
                    // We got an error : exit
                    clonedRegistries.clear();
                    return false;
                }
            }
            catch ( Exception e )
            {
                // We got an error : exit
                clonedRegistries.clear();
                return false;
            }
        }

        // We can now delete the cloned registries before exiting
        clonedRegistries.clear();

        return true;
    }


    /**
     * {@inheritDoc}
     */
    public boolean verify( String... schemas ) throws Exception
    {
        return verify( toArray( schemas ) );
    }


    /**
     * {@inheritDoc}
     */
    public void setSchemaLoader( SchemaLoader schemaLoader )
    {
        this.schemaLoader = schemaLoader;
    }


    /**
     * @return the namingContext
     */
    public LdapDN getNamingContext()
    {
        return namingContext;
    }


    /**
     * Initializes the SchemaService
     *
     * @throws Exception If the initialization fails
     */
    public void initialize() throws Exception
    {
        try
        {
            schemaModificationAttributesDN = new LdapDN( SchemaConstants.SCHEMA_MODIFICATIONS_DN );
            schemaModificationAttributesDN
                .normalize( getRegistries().getAttributeTypeRegistry().getNormalizerMapping() );
        }
        catch ( NamingException e )
        {
            throw new RuntimeException( e );
        }
    }


    /**
     * {@inheritDoc}
     */
    public SchemaLoader getLoader()
    {
        return schemaLoader;
    }


    //-----------------------------------------------------------------------------------
    // Immutable accessors
    //-----------------------------------------------------------------------------------
    /**
     * {@inheritDoc}
     */
    public AttributeTypeRegistry getAttributeTypeRegistry()
    {
        return new ImmutableAttributeTypeRegistry( registries.getAttributeTypeRegistry() );
    }


    /**
     * {@inheritDoc}
     */
    public ComparatorRegistry getComparatorRegistry()
    {
        return new ImmutableComparatorRegistry( registries.getComparatorRegistry() );
    }


    /**
     * {@inheritDoc}
     */
    public DITContentRuleRegistry getDITContentRuleRegistry()
    {
        return new ImmutableDITContentRuleRegistry( registries.getDitContentRuleRegistry() );
    }


    /**
     * {@inheritDoc}
     */
    public DITStructureRuleRegistry getDITStructureRuleRegistry()
    {
        return new ImmutableDITStructureRuleRegistry( registries.getDitStructureRuleRegistry() );
    }


    /**
     * {@inheritDoc}
     */
    public MatchingRuleRegistry getMatchingRuleRegistry()
    {
        return new ImmutableMatchingRuleRegistry( registries.getMatchingRuleRegistry() );
    }


    /**
     * {@inheritDoc}
     */
    public MatchingRuleUseRegistry getMatchingRuleUseRegistry()
    {
        return new ImmutableMatchingRuleUseRegistry( registries.getMatchingRuleUseRegistry() );
    }


    /**
     * {@inheritDoc}
     */
    public NameFormRegistry getNameFormRegistry()
    {
        return new ImmutableNameFormRegistry( registries.getNameFormRegistry() );
    }


    /**
     * {@inheritDoc}
     */
    public NormalizerRegistry getNormalizerRegistry()
    {
        return new ImmutableNormalizerRegistry( registries.getNormalizerRegistry() );
    }


    /**
     * {@inheritDoc}
     */
    public ObjectClassRegistry getObjectClassRegistry()
    {
        return new ImmutableObjectClassRegistry( registries.getObjectClassRegistry() );
    }


    /**
     * {@inheritDoc}
     */
    public LdapSyntaxRegistry getLdapSyntaxRegistry()
    {
        return new ImmutableLdapSyntaxRegistry( registries.getLdapSyntaxRegistry() );
    }


    /**
     * {@inheritDoc}
     */
    public SyntaxCheckerRegistry getSyntaxCheckerRegistry()
    {
        return new ImmutableSyntaxCheckerRegistry( registries.getSyntaxCheckerRegistry() );
    }


    /**
     * {@inheritDoc}
     */
    public AttributeType lookupAttributeTypeRegistry( String oid ) throws NamingException
    {
        return registries.getAttributeTypeRegistry().lookup( StringTools.toLowerCase( oid ).trim() );
    }


    /**
     * {@inheritDoc}
     */
    public LdapComparator<?> lookupComparatorRegistry( String oid ) throws NamingException
    {
        return registries.getComparatorRegistry().lookup( oid );
    }


    /**
     * Check that the given OID exists in the globalOidRegistry.
     */
    private boolean checkOidExist( SchemaObject schemaObject )
    {
        return registries.getGlobalOidRegistry().hasOid( schemaObject.getOid() );
    }


    /**
     * Retrieve the schema name for a specific SchemaObject, or return "other" if none is found.
     */
    private String getSchemaName( SchemaObject schemaObject )
    {
        String schemaName = StringTools.toLowerCase( schemaObject.getSchemaName() );

        if ( schemaLoader.getSchema( schemaName ) == null )
        {
            return MetaSchemaConstants.SCHEMA_OTHER;
        }
        else
        {
            return schemaName;
        }
    }


    //-----------------------------------------------------------------------------------
    // SchemaObject operations
    //-----------------------------------------------------------------------------------
    /**
     * {@inheritDoc}
     */
    public boolean add( SchemaObject schemaObject ) throws Exception
    {
        // First, clear the errors
        errors.clear();

        // Clone the schemaObject
        SchemaObject copy = schemaObject.copy();

        if ( registries.isRelaxed() )
        {
            // Apply the addition right away
            registries.add( errors, copy );

            return errors.isEmpty();
        }
        else
        {
            // Clone, apply, check, then apply again if ok
            // The new schemaObject's OID must not already exist
            if ( checkOidExist( copy ) )
            {
                Throwable error = new LdapSchemaViolationException( "Oid " + schemaObject.getOid()
                    + " for new schema entity is not unique.", ResultCodeEnum.OTHER );
                errors.add( error );

                return false;
            }

            // Build the new AttributeType from the given entry
            String schemaName = getSchemaName( copy );

            // At this point, the constructed AttributeType has not been checked against the 
            // existing Registries. It may be broken (missing SUP, or such), it will be checked
            // there, if the schema and the AttributeType are both enabled.
            Schema schema = getLoadedSchema( schemaName );

            if ( schema == null )
            {
                // The SchemaObject must be associated with an existing schema
                String msg = "Cannot inject the SchemaObject " + copy.getOid()
                    + " as it's not associated with a schema";
                LOG.info( msg );
                Throwable error = new LdapSchemaViolationException( msg, ResultCodeEnum.OTHER );
                errors.add( error );
                return false;
            }

            if ( schema.isEnabled() && copy.isEnabled() )
            {
                // As we may break the registries, work on a cloned registries
                Registries clonedRegistries = registries.clone();

                // Inject the new SchemaObject in the cloned registries
                clonedRegistries.add( errors, copy );

                // Remove the cloned registries
                clonedRegistries.clear();

                // If we didn't get any error, apply the addition to the real retistries
                if ( errors.isEmpty() )
                {
                    // Apply the addition to the real registries
                    registries.add( errors, copy );

                    LOG.debug( "Added {} into the enabled schema {}", copy.getName(), schemaName );

                    return true;
                }
                else
                {
                    // We have some error : reject the addition and get out
                    String msg = "Cannot add the SchemaObject " + copy.getOid() + " into the registries, "
                        + "the resulting registries would be inconsistent :" + StringTools.listToString( errors );
                    LOG.info( msg );

                    return false;
                }
            }
            else
            {
                // At least, we register the OID in the globalOidRegistry, and associates it with the
                // schema
                registries.associateWithSchema( errors, copy );

                LOG.debug( "Added {} into the disabled schema {}", copy.getName(), schemaName );
                return errors.isEmpty();
            }
        }
    }


    /**
     * {@inheritDoc}
     */
    public boolean delete( SchemaObject schemaObject ) throws Exception
    {
        // First, clear the errors
        errors.clear();

        if ( registries.isRelaxed() )
        {
            // Apply the addition right away
            registries.delete( errors, schemaObject );

            return errors.isEmpty();
        }
        else
        {
            // Clone, apply, check, then apply again if ok
            // The new schemaObject's OID must exist
            if ( !checkOidExist( schemaObject ) )
            {
                Throwable error = new LdapSchemaViolationException( "Oid " + schemaObject.getOid()
                    + " for new schema entity does not exist.", ResultCodeEnum.OTHER );
                errors.add( error );
                return false;
            }

            // Build the new AttributeType from the given entry
            SchemaObject toDelete = registries.getGlobalOidRegistry().getSchemaObject( schemaObject.getOid() );

            // First check that this SchemaObject does not have any referencing SchemaObjects
            Set<SchemaObjectWrapper> referencing = registries.getReferencing( toDelete );

            if ( ( referencing != null ) && !referencing.isEmpty() )
            {
                String msg = "Cannot remove  " + schemaObject.getOid()
                    + " for the registries, it would become inconsistent. The following SchemaOjects are "
                    + "referencing this SchemaObject : " + StringTools.setToString( referencing );

                Throwable error = new LdapSchemaViolationException( msg, ResultCodeEnum.OTHER );
                errors.add( error );
                return false;
            }

            String schemaName = getSchemaName( toDelete );

            // At this point, the deleted AttributeType may be referenced, it will be checked
            // there, if the schema and the AttributeType are both enabled.
            Schema schema = getLoadedSchema( schemaName );

            if ( schema == null )
            {
                // The SchemaObject must be associated with an existing schema
                String msg = "Cannot delete the SchemaObject " + schemaObject.getOid()
                    + " as it's not associated with a schema";
                LOG.info( msg );
                Throwable error = new LdapSchemaViolationException( msg, ResultCodeEnum.OTHER );
                errors.add( error );
                return false;
            }

            if ( schema.isEnabled() && schemaObject.isEnabled() )
            {
                // As we may break the registries, work on a cloned registries
                Registries clonedRegistries = registries.clone();

                // Delete the SchemaObject from the cloned registries
                clonedRegistries.delete( errors, toDelete );

                // Remove the cloned registries
                clonedRegistries.clear();

                // If we didn't get any error, apply the deletion to the real retistries
                if ( errors.isEmpty() )
                {
                    // Apply the deletion to the real registries
                    registries.delete( errors, toDelete );

                    LOG.debug( "Removed {} from the enabled schema {}", toDelete.getName(), schemaName );

                    return true;
                }
                else
                {
                    // We have some error : reject the deletion and get out
                    String msg = "Cannot deete the SchemaObject " + schemaObject.getOid() + " from the registries, "
                        + "the resulting registries would be inconsistent :" + StringTools.listToString( errors );
                    LOG.info( msg );

                    return false;
                }
            }
            else
            {
                // At least, we register the OID in the globalOidRegistry, and associates it with the
                // schema
                registries.associateWithSchema( errors, schemaObject );

                LOG.debug( "Removed {} from the disabled schema {}", schemaObject.getName(), schemaName );
                return errors.isEmpty();
            }
        }
    }


    /**
     * {@inheritDoc}
     */
    public Map<String, OidNormalizer> getNormalizerMapping()
    {
        return registries.getAttributeTypeRegistry().getNormalizerMapping();
    }


    /**
     * {@inheritDoc}
     */
    public OidRegistry getOidRegistry()
    {
        return registries.getGlobalOidRegistry();
    }


    /**
     * {@inheritDoc}
     */
    public Schema getLoadedSchema( String schemaName )
    {
        return schemaLoader.getSchema( schemaName );
    }


    /**
     * {@inheritDoc}
     */
    public boolean isSchemaLoaded( String schemaName )
    {
        try
        {
            Schema schema = schemaLoader.getSchema( schemaName );
            return schema != null;
        }
        catch ( Exception e )
        {
            return false;
        }
    }


    /**
     * {@inheritDoc}
     */
    public SchemaObject unregisterAttributeType( String attributeTypeOid ) throws NamingException
    {
        return registries.getAttributeTypeRegistry().unregister( attributeTypeOid );
    }


    /**
     * {@inheritDoc}
     */
    public SchemaObject unregisterComparator( String comparatorOid ) throws NamingException
    {
        return registries.getComparatorRegistry().unregister( comparatorOid );
    }


    /**
     * {@inheritDoc}
     */
    public SchemaObject unregisterDitControlRule( String ditControlRuleOid ) throws NamingException
    {
        return registries.getDitContentRuleRegistry().unregister( ditControlRuleOid );
    }


    /**
     * {@inheritDoc}
     */
    public SchemaObject unregisterDitStructureRule( String ditStructureRuleOid ) throws NamingException
    {
        return registries.getDitStructureRuleRegistry().unregister( ditStructureRuleOid );
    }


    /**
     * {@inheritDoc}
     */
    public SchemaObject unregisterLdapSyntax( String ldapSyntaxOid ) throws NamingException
    {
        return registries.getLdapSyntaxRegistry().unregister( ldapSyntaxOid );
    }


    /**
     * {@inheritDoc}
     */
    public SchemaObject unregisterMatchingRule( String matchingRuleOid ) throws NamingException
    {
        return registries.getMatchingRuleRegistry().unregister( matchingRuleOid );
    }


    /**
     * {@inheritDoc}
     */
    public SchemaObject unregisterMatchingRuleUse( String matchingRuleUseOid ) throws NamingException
    {
        return registries.getMatchingRuleUseRegistry().unregister( matchingRuleUseOid );
    }


    /**
     * {@inheritDoc}
     */
    public SchemaObject unregisterNameForm( String nameFormOid ) throws NamingException
    {
        return registries.getNameFormRegistry().unregister( nameFormOid );
    }


    /**
     * {@inheritDoc}
     */
    public SchemaObject unregisterNormalizer( String normalizerOid ) throws NamingException
    {
        return registries.getNormalizerRegistry().unregister( normalizerOid );
    }


    /**
     * {@inheritDoc}
     */
    public SchemaObject unregisterObjectClass( String objectClassOid ) throws NamingException
    {
        return registries.getObjectClassRegistry().unregister( objectClassOid );
    }


    /**
     * {@inheritDoc}
     */
    public SchemaObject unregisterSyntaxChecker( String syntaxCheckerOid ) throws NamingException
    {
        return registries.getSyntaxCheckerRegistry().unregister( syntaxCheckerOid );
    }


    /**
     * Tells if the SchemaManager is permissive or if it must be checked 
     * against inconsistencies.
     *
     * @return True if SchemaObjects can be added even if they break the consistency 
     */
    public boolean isRelaxed()
    {
        return isRelaxed;
    }


    /**
     * Tells if the SchemaManager is strict.
     *
     * @return True if SchemaObjects cannot be added if they break the consistency 
     */
    public boolean isStrict()
    {
        return !isRelaxed;
    }


    /**
     * Change the SchemaManager to a relaxed mode, where invalid SchemaObjects
     * can be registered.
     */
    public void setRelaxed()
    {
        isRelaxed = RELAXED;
    }


    /**
     * Change the SchemaManager to a strict mode, where invalid SchemaObjects
     * cannot be registered.
     */
    public void setStrict()
    {
        isRelaxed = STRICT;
    }


    /**
     * {@inheritDoc}
     */
    public boolean isDisabled( String schemaName )
    {
        Schema schema = registries.getLoadedSchema( schemaName );

        return ( schema != null ) && schema.isDisabled();
    }


    /**
     * {@inheritDoc}
     */
    public boolean isDisabled( Schema schema )
    {
        return ( schema != null ) && schema.isDisabled();
    }


    /**
     * {@inheritDoc}
     */
    public boolean isEnabled( String schemaName )
    {
        Schema schema = registries.getLoadedSchema( schemaName );

        return ( schema != null ) && schema.isEnabled();
    }


    /**
     * {@inheritDoc}
     */
    public boolean isEnabled( Schema schema )
    {
        return ( schema != null ) && schema.isEnabled();
    }
}
