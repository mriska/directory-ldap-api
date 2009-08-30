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
package org.apache.directory.shared.ldap.schema.normalizers;


import javax.naming.NamingException;

import org.apache.directory.shared.ldap.entry.Value;
import org.apache.directory.shared.ldap.schema.Normalizer;
import org.apache.directory.shared.ldap.util.SynchronizedLRUMap;


/**
 * Caches previously normalized values.
 * 
 * @author <a href="mailto:dev@directory.apache.org">Apache Directory Project</a>
 * @version $Rev$
 */
public class CachingNormalizer extends Normalizer
{
    /** The serial UID */
    public static final long serialVersionUID = 1L;

    /** Cache maximum size default */
    public static final int CACHE_MAX = 250;

    /** Least recently used cache */
    private final SynchronizedLRUMap cache;

    /** The underlying decorated Normalizer */
    protected final Normalizer normalizer;


    // ------------------------------------------------------------------------
    // C O N S T R U C T O R S
    // ------------------------------------------------------------------------

    /**
     * Creates a CachingNormalizer that decorates another normalizer using a
     * default cache size.  This Normalizer delegates 
     * 
     * @param oid The MR OID to use with this Normalizer
     * @param normalizer the underlying Normalizer being decorated
     */
    public CachingNormalizer( Normalizer normalizer )
    {
        this( normalizer, CACHE_MAX );
    }


    /**
     * Creates a CachingNormalizer that decorates another normalizer using a
     * specified cache size.
     * 
     * @param normalizer the underlying Normalizer being decorated
     * @param cacheSz the maximum size of the name cache
     */
    public CachingNormalizer( Normalizer normalizer, int cacheSz )
    {
        super( normalizer.getOid() );
        this.normalizer = normalizer;
        cache = new SynchronizedLRUMap( cacheSz );
    }

    
    /**
     * Overrides default behavior by returning the OID of the wrapped 
     * Normalizer.
     */
    @Override
    public String getOid()
    {
        return normalizer.getOid();
    }
    
    
    /**
     * Overrides default behavior by setting the OID of the wrapped Normalizer.
     * 
     * @param oid the object identifier to set
     */
    @Override
    public void setOid( String oid )
    {
    	super.setOid( oid );
        normalizer.setOid( oid );
    }
    

    /**
     * {@inheritDoc}
     */
    public Value<?> normalize( Value<?> value ) throws NamingException
    {
        if ( value == null )
        {
            return null;
        }

        Value<?> result =(Value<?>)cache.get( value );

        if ( result != null )
        {
            return result;
        }

        Value<?> normalized = normalizer.normalize( value );
        cache.put( value, normalized );
        return normalized;
    }


    /**
     * {@inheritDoc}
     */
    public String normalize( String value ) throws NamingException
    {
        if ( value == null )
        {
            return null;
        }

        String normalized =(String)cache.get( value );

        if ( normalized != null )
        {
            return normalized;
        }

        normalized = normalizer.normalize( value );
        cache.put( value, normalized );
        return normalized;
    }
}
