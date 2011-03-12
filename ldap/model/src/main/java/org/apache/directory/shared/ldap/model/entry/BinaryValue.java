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
package org.apache.directory.shared.ldap.model.entry;


import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.Arrays;
import java.util.Comparator;

import org.apache.directory.shared.i18n.I18n;
import org.apache.directory.shared.ldap.model.exception.LdapException;
import org.apache.directory.shared.ldap.model.schema.AttributeType;
import org.apache.directory.shared.ldap.model.schema.AbstractLdapComparator;
import org.apache.directory.shared.ldap.model.schema.Normalizer;
import org.apache.directory.shared.ldap.model.schema.SchemaManager;
import org.apache.directory.shared.ldap.model.schema.SyntaxChecker;
import org.apache.directory.shared.ldap.model.schema.comparators.ByteArrayComparator;
import org.apache.directory.shared.util.StringConstants;
import org.apache.directory.shared.util.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * A server side schema aware wrapper around a binary attribute value.
 * This value wrapper uses schema information to syntax check values,
 * and to compare them for equality and ordering.  It caches results
 * and invalidates them when the wrapped value changes.
 *
 * @author <a href="mailto:dev@directory.apache.org">Apache Directory Project</a>
 */
public class BinaryValue extends AbstractValue<byte[]>
{
    /** Used for serialization */
    public static final long serialVersionUID = 2L;

    /** logger for reporting errors that might not be handled properly upstream */
    private static final Logger LOG = LoggerFactory.getLogger( BinaryValue.class );


    /**
     * Creates a BinaryValue without an initial wrapped value.
     */
    public BinaryValue()
    {
        wrappedValue = null;
        normalized = false;
        valid = null;
        normalizedValue = null;
    }


    /**
     * Creates a BinaryValue without an initial wrapped value.
     *
     * @param attributeType the schema type associated with this BinaryValue
     */
    public BinaryValue( AttributeType attributeType )
    {
        if ( attributeType == null )
        {
            String message = I18n.err( I18n.ERR_04442_NULL_AT_NOT_ALLOWED );
            LOG.error( message );
            throw new IllegalArgumentException( message );
        }

        if ( attributeType.getSyntax() == null )
        {
            throw new IllegalArgumentException( I18n.err( I18n.ERR_04445 ) );
        }

        if ( attributeType.getSyntax().isHumanReadable() )
        {
            LOG.warn( "Treating a value of a human readible attribute {} as binary: ", attributeType.getName() );
        }

        this.attributeType = attributeType;
    }


    /**
     * Creates a BinaryValue with an initial wrapped binary value.
     *
     * @param value the binary value to wrap which may be null, or a zero length byte array
     */
    public BinaryValue( byte[] value )
    {
        if ( value != null )
        {
            this.wrappedValue = new byte[value.length];
            System.arraycopy( value, 0, this.wrappedValue, 0, value.length );
        }
        else
        {
            this.wrappedValue = null;
        }

        normalized = false;
        valid = null;
        normalizedValue = null;
    }


    /**
     * Creates a BinaryValue with an initial wrapped binary value.
     *
     * @param attributeType the schema type associated with this BinaryValue
     * @param value the binary value to wrap which may be null, or a zero length byte array
     */
    public BinaryValue( AttributeType attributeType, byte[] value )
    {
        this( attributeType );
        SyntaxChecker syntaxChecker = attributeType.getSyntax().getSyntaxChecker();

        if ( syntaxChecker != null )
        {
            if ( syntaxChecker.isValidSyntax( value ) )
            {
                this.wrappedValue = value;
            }
            else
            {
                String msg = I18n.err( I18n.ERR_04479_INVALID_SYNTAX_VALUE, value, attributeType.getName() );
                LOG.info( msg );
                throw new IllegalArgumentException( msg );
            }
        }
    }


    /**
     * Gets a direct reference to the normalized representation for the
     * wrapped value of this ServerValue wrapper. Implementations will most
     * likely leverage the attributeType this value is associated with to
     * determine how to properly normalize the wrapped value.
     *
     * @return the normalized version of the wrapped value
     * @throws org.apache.directory.shared.ldap.model.exception.LdapException if schema entity resolution fails or normalization fails
     */
    public byte[] getNormalizedValue()
    {
        if ( isNull() )
        {
            return null;
        }

        if ( !normalized )
        {
            try
            {
                normalize();
            }
            catch ( LdapException ne )
            {
                String message = "Cannot normalize the value :" + ne.getLocalizedMessage();
                LOG.warn( message );
                normalized = false;
            }
        }

        if ( normalizedValue != null )
        {
            byte[] copy = new byte[normalizedValue.length];
            System.arraycopy( normalizedValue, 0, copy, 0, normalizedValue.length );
            return copy;
        }
        else
        {
            return StringConstants.EMPTY_BYTES;
        }
    }


    /**
     * Gets the normalized (canonical) representation for the wrapped string.
     * If the wrapped String is null, null is returned, otherwise the normalized
     * form is returned.  If no the normalizedValue is null, then this method
     * will attempt to generate it from the wrapped value: repeated calls to
     * this method do not unnecessarily normalize the wrapped value.  Only changes
     * to the wrapped value result in attempts to normalize the wrapped value.
     *
     * @return a reference to the normalized version of the wrapped value
     */
    public byte[] getNormalizedValueReference()
    {
        if ( isNull() )
        {
            return null;
        }

        if ( !isNormalized() )
        {
            try
            {
                normalize();
            }
            catch ( LdapException ne )
            {
                String message = "Cannot normalize the value :" + ne.getLocalizedMessage();
                LOG.warn( message );
                normalized = false;
            }
        }

        if ( normalizedValue != null )
        {
            return normalizedValue;
        }
        else
        {
            return wrappedValue;
        }
    }


    /**
     * Normalize the value. For a client String value, applies the given normalizer.
     * 
     * It supposes that the client has access to the schema in order to select the
     * appropriate normalizer.
     * 
     * @param normalizer The normalizer to apply to the value
     * @exception LdapException If the value cannot be normalized
     */
    public final void normalize( Normalizer normalizer ) throws LdapException
    {
        if ( normalizer != null )
        {
            if ( wrappedValue == null )
            {
                normalizedValue = wrappedValue;
                normalized = true;
                same = true;
            }
            else
            {
                normalizedValue = normalizer.normalize( this ).getBytes();
                normalized = true;
                same = Arrays.equals( wrappedValue, normalizedValue );
            }
        }
        else
        {
            normalizedValue = wrappedValue;
            normalized = false;
            same = true;
        }
    }


    /**
     * {@inheritDoc}
     */
    public void normalize() throws LdapException
    {
        if ( isNormalized() )
        {
            // Bypass the normalization if it has already been done. 
            return;
        }

        if ( attributeType != null )
        {
            Normalizer normalizer = getNormalizer();
            normalize( normalizer );
        }
        else
        {
            normalizedValue = wrappedValue;
            normalized = true;
            same = true;
        }
    }


    /**
     *
     * @see ServerValue#compareTo(Value)
     * @throws IllegalStateException on failures to extract the comparator, or the
     * normalizers needed to perform the required comparisons based on the schema
     */
    public int compareTo( Value<byte[]> value )
    {
        if ( isNull() )
        {
            if ( ( value == null ) || value.isNull() )
            {
                return 0;
            }
            else
            {
                return -1;
            }
        }
        else
        {
            if ( ( value == null ) || value.isNull() )
            {
                return 1;
            }
        }

        BinaryValue binaryValue = ( BinaryValue ) value;

        if ( attributeType != null )
        {
            try
            {
                AbstractLdapComparator<byte[]> comparator = getLdapComparator();

                if ( comparator != null )
                {
                    return comparator
                        .compare( getNormalizedValueReference(), binaryValue.getNormalizedValueReference() );
                }
                else
                {
                    return new ByteArrayComparator( null ).compare( getNormalizedValueReference(), binaryValue
                        .getNormalizedValueReference() );
                }
            }
            catch ( LdapException e )
            {
                String msg = I18n.err( I18n.ERR_04443, Arrays.toString( getReference() ), value );
                LOG.error( msg, e );
                throw new IllegalStateException( msg, e );
            }
        }
        else
        {
            return new ByteArrayComparator( null ).compare( getNormalizedValue(), binaryValue.getNormalizedValue() );
        }
    }


    // -----------------------------------------------------------------------
    // Object Methods
    // -----------------------------------------------------------------------
    /**
     * @see Object#hashCode()
     * @return the instance's hashcode 
     */
    public int hashCode()
    {
        if ( h == 0 )
        {
            // return zero if the value is null so only one null value can be
            // stored in an attribute - the string version does the same
            if ( isNull() )
            {
                return 0;
            }

            byte[] normalizedValue = getNormalizedValueReference();
            h = Arrays.hashCode( normalizedValue );
        }

        return h;
    }


    /**
     * Checks to see if this BinaryValue equals the supplied object.
     *
     * This equals implementation overrides the BinaryValue implementation which
     * is not schema aware.
     * @throws IllegalStateException on failures to extract the comparator, or the
     * normalizers needed to perform the required comparisons based on the schema
     */
    public boolean equals( Object obj )
    {
        if ( this == obj )
        {
            return true;
        }

        if ( !( obj instanceof BinaryValue ) )
        {
            return false;
        }

        BinaryValue other = ( BinaryValue ) obj;

        if ( isNull() )
        {
            return other.isNull();
        }

        // If we have an attributeType, it must be equal
        // We should also use the comparator if we have an AT
        if ( attributeType != null )
        {
            if ( other.attributeType != null )
            {
                if ( !attributeType.equals( other.attributeType ) )
                {
                    return false;
                }
            }
            else
            {
                other.attributeType = attributeType;
            }
        }
        else if ( other.attributeType != null )
        {
            attributeType = other.attributeType;
        }

        // Shortcut : if the values are equals, no need to compare
        // the normalized values
        if ( Arrays.equals( wrappedValue, other.wrappedValue ) )
        {
            return true;
        }

        if ( attributeType != null )
        {
            // We have an AttributeType, we eed to use the comparator
            try
            {
                Comparator<byte[]> comparator = ( Comparator<byte[]> ) getLdapComparator();

                // Compare normalized values
                if ( comparator == null )
                {
                    return Arrays.equals( getNormalizedValueReference(), other.getNormalizedValueReference() );
                }
                else
                {
                    return comparator.compare( getNormalizedValueReference(), other.getNormalizedValueReference() ) == 0;
                }
            }
            catch ( LdapException ne )
            {
                return false;
            }

        }
        else
        {
            // now unlike regular values we have to compare the normalized values
            return Arrays.equals( getNormalizedValueReference(), other.getNormalizedValueReference() );
        }
    }


    // -----------------------------------------------------------------------
    // Private Helper Methods (might be put into abstract base class)
    // -----------------------------------------------------------------------
    /**
     * @return a copy of the current value
     */
    public BinaryValue clone()
    {
        BinaryValue clone = ( BinaryValue ) super.clone();

        if ( normalizedValue != null )
        {
            clone.normalizedValue = new byte[normalizedValue.length];
            System.arraycopy( normalizedValue, 0, clone.normalizedValue, 0, normalizedValue.length );
        }

        if ( wrappedValue != null )
        {
            clone.wrappedValue = new byte[wrappedValue.length];
            System.arraycopy( wrappedValue, 0, clone.wrappedValue, 0, wrappedValue.length );
        }

        return clone;
    }


    /**
     * {@inheritDoc}
     */
    public byte[] get()
    {
        if ( wrappedValue == null )
        {
            return null;
        }

        final byte[] copy = new byte[wrappedValue.length];
        System.arraycopy( wrappedValue, 0, copy, 0, wrappedValue.length );

        return copy;
    }


    /**
     * Tells if the current value is Binary or String
     * 
     * @return <code>true</code> if the value is Binary, <code>false</code> otherwise
     */
    public boolean isBinary()
    {
        return true;
    }


    /**
     * @return The length of the interned value
     */
    public int length()
    {
        return wrappedValue != null ? wrappedValue.length : 0;
    }


    /**
     * Get the wrapped value as a byte[]. This method returns a copy of 
     * the wrapped byte[].
     * 
     * @return the wrapped value as a byte[]
     */
    public byte[] getBytes()
    {
        return get();
    }


    /**
     * Get the wrapped value as a String.
     *
     * @return the wrapped value as a String
     */
    public String getString()
    {
        return Strings.utf8ToString(wrappedValue);
    }


    /**
     * {@inheritDoc}
     */
    public void readExternal( ObjectInput in ) throws IOException, ClassNotFoundException
    {
        // Read the wrapped value, if it's not null
        int wrappedLength = in.readInt();

        if ( wrappedLength >= 0 )
        {
            wrappedValue = new byte[wrappedLength];

            if ( wrappedLength > 0 &&  in.read( wrappedValue ) == -1 )
            {
                throw new IOException( I18n.err( I18n.ERR_04480_END_OF_STREAM ) );
            }
        }

        // Read the isNormalized flag
        normalized = in.readBoolean();

        if ( normalized )
        {
            int normalizedLength = in.readInt();

            if ( normalizedLength >= 0 )
            {
                normalizedValue = new byte[normalizedLength];

                if ( normalizedLength > 0 )
                {
                    in.read( normalizedValue );
                }
            }
        }

        h = 0;
    }


    /**
     * {@inheritDoc}
     */
    public void writeExternal( ObjectOutput out ) throws IOException
    {
        // Write the wrapped value, if it's not null
        if ( wrappedValue != null )
        {
            out.writeInt( wrappedValue.length );

            if ( wrappedValue.length > 0 )
            {
                out.write( wrappedValue, 0, wrappedValue.length );
            }
        }
        else
        {
            out.writeInt( -1 );
        }

        // Write the isNormalized flag
        if ( normalized )
        {
            out.writeBoolean( true );

            // Write the normalized value, if not null
            if ( normalizedValue != null )
            {
                out.writeInt( normalizedValue.length );

                if ( normalizedValue.length > 0 )
                {
                    out.write( normalizedValue, 0, normalizedValue.length );
                }
            }
            else
            {
                out.writeInt( -1 );
            }
        }
        else
        {
            out.writeBoolean( false );
        }
    }

    
    /**
     * {@inheritDoc}
     */
    public static BinaryValue deserialize( SchemaManager schemaManager, ObjectInput in ) throws IOException
    {
        return (BinaryValue)AbstractValue.deserialize( schemaManager, in );
    }


    /**
     * Dumps binary in hex with label.
     *
     * @see Object#toString()
     */
    public String toString()
    {
        if ( wrappedValue == null )
        {
            return "null";
        }
        else if ( wrappedValue.length > 16 )
        {
            // Just dump the first 16 bytes...
            byte[] copy = new byte[16];

            System.arraycopy( wrappedValue, 0, copy, 0, 16 );

            return "'" + Strings.dumpBytes(copy) + "...'";
        }
        else
        {
            return "'" + Strings.dumpBytes(wrappedValue) + "'";
        }
    }
}