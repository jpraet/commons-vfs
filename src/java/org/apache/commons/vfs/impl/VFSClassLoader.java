package org.apache.commons.vfs.impl;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Enumeration;
import java.security.SecureClassLoader;
import java.security.cert.Certificate;
import java.security.CodeSource;
import java.security.PermissionCollection;
import java.security.Permissions;
import java.security.Permission;
import java.util.jar.Attributes.Name;
import java.net.URL;
import java.net.MalformedURLException;
import java.io.IOException;
import org.apache.commons.vfs.FileSystemManager;
import org.apache.commons.vfs.FileObject;
import org.apache.commons.vfs.FileContent;
import org.apache.commons.vfs.FileSystemException;
import org.apache.commons.vfs.FileType;


/**
 *
 * A class loader that can load classes and resources from a search path
 * VFS FileObjects refering both to folders and JAR files. Any FileObject
 * of type {@link FileType#FILE} is asumed to be a JAR and is opened
 * by creating a layered file system with the "jar" scheme.
 * </p><p>
 * TODO - Test this with signed Jars and a SecurityManager.
 *
 * @see FileSystemManager#createFileSystem
 * @author <a href="mailto:brian@mmmanager.org">Brian Olsen</a>
 * @version $Revision: 1.3 $ $Date: 2002/10/21 01:40:38 $
 */
public class VFSClassLoader
    extends SecureClassLoader
{
    private ArrayList resources;
    private FileSystemManager manager;

    /**
     * Constructors a new VFSClassLoader for the given FileObjects.
     * The FileObjects will be searched in the order specified.
     *
     * @param files the FileObjects to load the classes and resources from.
     *
     * @param manager
     *      the FileManager to use when trying create a layered Jar file
     *      system.
     */
    public VFSClassLoader( FileObject[] files,
                           FileSystemManager manager )
    {
        super();

        this.manager = manager;
        resources = new ArrayList( files.length );
        for ( int i = 0; i < files.length; i++ )
        {
            addFileObject( files[i] );
        }
    }

    /**
     * Constructors a new VFSClassLoader for the given FileObjects.
     * The FileObjects will be searched in the order specified.
     *
     * @param files the FileObjects to load the classes and resources from.
     *
     * @param manager
     *      the FileManager to use when trying create a layered Jar file
     *      system.
     *
     * @param parent the parent class loader for delegation.
     */
    public VFSClassLoader( FileObject[] files,
                           FileSystemManager manager,
                           ClassLoader parent )
    {
        super( parent );

        this.manager = manager;
        resources = new ArrayList( files.length );
        for ( int i = 0; i < files.length; i++ )
        {
            addFileObject( files[i] );
        }
    }

    /**
     * Appends the specified FileObject to the list of FileObjects to search
     * for classes and resources.
     *
     * @param file the FileObject to append to the search path.
     */
    protected void addFileObject( FileObject file )
    {
        resources.add( file );
    }

    /**
     * Finds and loads the class with the specified name from the search
     * path.
     * @throws ClassNotFoundException if the class is not found.
     */
    protected Class findClass( String name ) throws ClassNotFoundException
    {
        try
        {
            String path = name.replace( '.', '/' ).concat( ".class" );
            Resource res = loadResource( path );
            if ( res == null )
            {
                throw new ClassNotFoundException(name);
            }
            return defineClass( name, res );
        }
        catch ( FileSystemException fse )
        {
            throw new ClassNotFoundException( name, fse );
        }
        catch ( IOException ioe )
        {
            throw new ClassNotFoundException( name, ioe );
        }
    }

    /**
     * Loads and verifies the class with name and located with res.
     */
    private Class defineClass( String name, Resource res )
        throws IOException, FileSystemException
    {
        URL url = res.getCodeSourceURL();

        int i = name.lastIndexOf( "." );
        if ( i != -1 )
        {
            String pkgName = name.substring( 0, i );
            Package pkg = getPackage( pkgName );
            if ( pkg != null )
            {
                if ( pkg.isSealed() )
                {
                    if ( !pkg.isSealed( url ) )
                    {
                        throw new FileSystemException( "vfs.impl/pkg-sealed-other-url", pkgName );
                    }
                }
                else
                {
                    if ( isSealed( res ) )
                    {
                        throw new FileSystemException( "vfs.impl/pkg-sealing-unsealed", pkgName );
                    }
                }
            }
            else
            {
                definePackage( pkgName, res, url );
            }
        }

        byte[] bytes = res.getBytes();
        Certificate[] certs =
            res.getFileObject().getContent().getCertificates();
        CodeSource cs = new CodeSource( url, certs );
        return defineClass( name, bytes, 0, bytes.length, cs );
    }

    /**
     * Reads attributes for the package and defines it.
     */
    private Package definePackage( String name, Resource res, URL url )
        throws FileSystemException
    {
        URL sealBase = null;
        final FileContent content =
            res.getFileObject().getParent().getContent();
        String specTitle = (String) content.getAttribute(
            Name.SPECIFICATION_TITLE.toString() );

        String specVersion = (String) content.getAttribute(
            Name.SPECIFICATION_VERSION.toString() );
        String specVendor = (String) content.getAttribute(
            Name.SPECIFICATION_VENDOR.toString() );
        String implTitle = (String) content.getAttribute(
            Name.IMPLEMENTATION_TITLE.toString() );
        String implVersion = (String) content.getAttribute(
            Name.IMPLEMENTATION_VERSION.toString() );
        String implVendor = (String) content.getAttribute(
            Name.IMPLEMENTATION_VENDOR.toString() );
        String seal = (String) content.getAttribute( Name.SEALED.toString() );

        if( "true".equalsIgnoreCase( seal ) )
        {
            sealBase = url;
        }
        return definePackage( name, specTitle, specVersion, specVendor,
                              implTitle, implVersion, implVendor, sealBase );
    }

    /**
     * Returns true if the we should seal the package where res resides.
     */
    private boolean isSealed( Resource res )
        throws FileSystemException
    {
        final FileContent content =
            res.getFileObject().getParent().getContent();
        String sealed = (String) content.getAttribute( Name.SEALED.toString() );

        return "true".equalsIgnoreCase( sealed );
    }


    /**
     * Calls super.getPermissions both for the code source and also
     * adds the permissions granted to the parent layers.
     */
    protected PermissionCollection getPermissions( CodeSource cs )
    {
        try
        {
            String url = cs.getLocation().toString();
            FileObject file = lookupFileObject( url );
            if ( file == null )
            {
                return super.getPermissions( cs );
            }

            FileObject parentLayer = file.getFileSystem().getParentLayer();
            if ( parentLayer == null )
            {
                return super.getPermissions( cs );
            }

            Permissions combi = new Permissions();
            PermissionCollection permCollect = super.getPermissions( cs );
            copyPermissions( permCollect, combi );

            for ( FileObject parent = parentLayer;
                  parent != null;
                  parent = parent.getFileSystem().getParentLayer() )
            {
                cs = new CodeSource( parent.getURL(),
                                     parent.getContent().getCertificates() );
                permCollect = super.getPermissions( cs );
                copyPermissions( permCollect, combi );
            }

            return combi;
        }
        catch ( FileSystemException fse )
        {
            throw new SecurityException( fse.getMessage() );
        }
        catch ( MalformedURLException mue )
        {
            throw new SecurityException( mue.getMessage() );
        }
    }

    /**
     * Copies the permissions from src to dest.
     */
    protected void copyPermissions( PermissionCollection src,
                                    PermissionCollection dest )
    {
        for ( Enumeration elem = src.elements(); elem.hasMoreElements(); )
        {
            final Permission permission = (Permission) elem.nextElement();
            dest.add( permission );
        }
    }

    /**
     * Does a reverse lookup to find the FileObject when we only have the
     * URL.
     */
    private FileObject lookupFileObject( String name )
    {
        Iterator it = resources.iterator();
        while ( it.hasNext() )
        {
            final FileObject object = (FileObject) it.next();
            if ( name.equals( object.getName().getURI() ) )
            {
                return object;
            }
        }
        return null;
    }

    /**
     * Finds the resource with the specified name from the search path.
     * This returns null if the resource is not found.
     */
    protected URL findResource( String name )
    {
        try
        {
            Resource res = loadResource( name );
            return res != null ? res.getURL() : null;
        }
        catch ( MalformedURLException mue )
        {
            return null;
        }
    }

    /**
     * Returns an Enumeration of all the resources in the search path
     * with the specified name.
     * TODO - Implement this.
     */
    protected Enumeration findResources( String name )
    {
        return null;
    }

    /**
     * Searches through the search path of for the first class or resource
     * with specified name.
     */
    private Resource loadResource( String name )
    {
        Iterator it = resources.iterator();
        while ( it.hasNext() )
        {
            try
            {
                Resource res = null;
                final FileObject object = (FileObject)it.next();
                if ( object.getType() == FileType.FILE )
                {
                    res = loadJarResource( object, name );
                }
                else if ( object.getType() == FileType.FOLDER )
                {
                    res = loadFolderResource( object, name );
                }
                if ( res != null )
                {
                    return res;
                }
            }
            catch ( FileSystemException fse )
            {
            }
        }
        return null;
    }

    Resource loadJarResource( FileObject jarFile, String name )
        throws FileSystemException
    {
        FileObject base = manager.createFileSystem( "jar", jarFile );
        FileObject file = base.resolveFile( name );
        if ( file.exists() )
        {
            return new Resource( jarFile, file );
        }
        return null;
    }

    Resource loadFolderResource( FileObject base, String name )
        throws FileSystemException
    {
        FileObject file = base.resolveFile( name );
        if ( file.exists() )
        {
            return new Resource( base, file );
        }
        return null;
    }
}