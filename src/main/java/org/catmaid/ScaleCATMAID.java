/**
 * License: GPL
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License 2
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package org.catmaid;

import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.awt.image.PixelGrabber;
import java.io.File;
import java.io.IOException;

import javax.imageio.ImageIO;

import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.converter.read.ARGBARGBDoubleConverter;
import net.imglib2.converter.read.ARGBDoubleARGBConverter;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.basictypeaccess.array.IntArray;
import net.imglib2.type.numeric.ARGBDoubleType;
import net.imglib2.type.numeric.ARGBType;

import org.catmaid.Tiler.Orientation;

/**
 * <p>A standalone command line application to generate the scale pyramid of an
 * existing scale level 0 tile set for the CATMAID interface.</p>
 * 
 * <p>Separation of scale level 0 tile generation and scaling is necessary to
 * enable the desired level of parallelization.  One would typically generate
 * the scale level 0 tile set parallelized in volumes that cover a moderate
 * number of source tiles.  Only after a <em>z</em>-section is fully exported,
 * it can be used to generate the scale pyramid.  I.e., scaling can be
 * parallelized by <em>z</em>-section but not within a <em>z</em>-section.</p>
 * 
 * <p>The program accepts the following parameters, type and default in
 * parantheses:</p>
 * <dl>
 * <dt>width</dt>
 * <dd>width of the source in scale level 0 pixels in <em>xyz</em> orientation
 * (long, 0)</dd>
 * <dt>height</dt>
 * <dd>height of the source in scale level 0 pixels in <em>xyz</em> orientation
 * (long, 0)</dd>
 * <dt>depth</dt>
 * <dd>depth of the source in scale level 0 pixels in <em>xyz</em> orientation
 * (long, 0)</dd>
 * <dt>orientation</dt>
 * <dd>orientation of the source, is used to permute width, height, depth
 * accordingly, possible values "xy", "xz", "zy" (string, "xy")</dd>
 * <dt>tileWidth</dt>
 * <dd>width of image tiles in pixels (int, 256)</dd>
 * <dt>tileHeight</dt>
 * <dd>height of image tiles in pixels (int, 256)</dd>
 * <dt>minZ</dt>
 * <dd>first <em>z</em>-section index (long, 0)</dd>
 * <dt>maxZ</dt>
 * <dd>last <em>z</em>-section index (long, depth-1)</dd>
 * <dt>basePath</dt>
 * <dd>base path to the scale level 0 tile set, that's where scaled tiles will
 * be exported too (string, "")</dd>
 * <dt>tilePattern</dt>
 * <dd>tilePattern the file name convention for tile coordinates without
 * extension and base path, must contain "&lt;s&gt;","&lt;z&gt;", "&lt;r&gt;",
 * "&lt;c&gt;" (string, "&lt;z&gt;/&lt;r&gt;_&lt;c&gt;_&lt;s&gt;")
 * <dt>format</dt>
 * <dd>image tile file format, e.g. "jpg" or "png" (string, "jpg")</dd>
 * <dt>quality</dt>
 * <dd>quality for jpg-compression if format is "jpg" (float, 0.85)</dd>
 * <dt>type</dt>
 * <dd>the type of export tiles, either "rgb or "gray" (string, "rgb")</dd>
 * </dl>
 * <p>Parameters are passed as properties to the JVM virtual machine, e.g.
 * <code>./java -jar ScaleCATMAID.jar</code></p>
 * @author Stephan Saalfeld <saalfeld@mpi-cbg.de>
 */
public class ScaleCATMAID
{
	static protected class Param
	{
		public Interval sourceInterval;
		public Orientation orientation;
		public int tileWidth;
		public int tileHeight;
		public long minZ;
		public long maxZ;
		public String basePath;
		public String tilePattern;
		public String format;
		public float quality;
		public int type;
	}
	
	private ScaleCATMAID(){}
	
	static protected Param parseParameters()
	{
		final Param p = new Param();
		final long width = Long.parseLong( System.getProperty( "width", "0" ) );
		final long height = Long.parseLong( System.getProperty( "height", "0" ) );
		final long depth = Long.parseLong( System.getProperty( "depth", "0" ) );
		p.sourceInterval = new FinalInterval( width, height, depth );
		final String orientation = System.getProperty( "orientation", "xy" );
		if ( orientation.equalsIgnoreCase( "xz" ) )
			p.orientation = Orientation.XZ;
		else if ( orientation.equalsIgnoreCase( "zy" ) )
			p.orientation = Orientation.ZY;
		else
			p.orientation = Orientation.XY;
		p.tileWidth = Integer.parseInt( System.getProperty( "tileWidth", "256" ) );
		p.tileHeight = Integer.parseInt( System.getProperty( "tileHeight", "256" ) );
		p.minZ = Long.parseLong( System.getProperty( "minZ", "0" ) );
		p.maxZ = Long.parseLong( System.getProperty( "maxZ", Long.toString( depth - 1 ) ) );
		p.basePath = System.getProperty( "basePath", "" );
		p.tilePattern = System.getProperty( "tilePattern", "<z>/<r>_<c>_<s>" );
		p.format = System.getProperty( "format", "jpg" );
		p.quality = Float.parseFloat( System.getProperty( "quality", "0.85" ) );
		final String type = System.getProperty( "type", "rgb" );
		if ( type.equalsIgnoreCase( "gray" ) || type.equalsIgnoreCase( "grey" ) )
			p.type = BufferedImage.TYPE_BYTE_GRAY;
		else
			p.type = BufferedImage.TYPE_INT_RGB;
		
		return p;
	}
	
	final static protected BufferedImage open(
			final String path,
			final BufferedImage alternative,
			final int type )
	{
		System.out.println( path );
		final File file = new File( path );
		if ( file.exists() )
		{
//			final Image tImg = toolkit.createImage( path );
			try
			{
				return ImageIO.read( new File( path ) );
			}
			catch ( final IOException e )
			{
				return alternative;
			}
		}
		else
			return alternative;
	}
	
	
	/**
	 * Generate scaled tiles from a range of an existing scale level 0 tile
	 * stack.
	 * 
	 * @param sourceInterval the dimensions of the level 0 tile stack in
	 * 		original x,y,z orientation 
	 * @param orientation the orientation of the level 0 tile stack, required
	 * 		to transpose sourceInterval accordingly
	 * @param tileWidth
	 * @param tileHeight
	 * @param minZ the first z-index to be scaled 
	 * @param maxZ the last z-index to be scaled
	 * @param basePath base path of the source, this is also where the scaled
	 * 		tiles will be exported
	 * @param tilePattern the file name convention for tile coordinates without
	 * 		extension and base path, must contain "&lt;s&gt;","&lt;z&gt;",
	 * 		"&lt;r&gt;", "&lt;c&gt;".
	 * @param format file format, e.g. "jpg" or "png"
	 * @param quality quality for jpg-compression if format is "jpg"
	 * @param type the type of export tiles, e.g.
	 * 		{@link BufferedImage#TYPE_BYTE_GRAY}
	 * 
	 * @throws Exception
	 */
	final public static void scale(
			final Interval sourceInterval,
			final Tiler.Orientation orientation,
			final int tileWidth,
			final int tileHeight,
			final long minZ,
			final long maxZ,
			final String basePath,
			final String tilePattern,
			final String format,
			final float quality,
			final int type ) throws Exception
	{
		final BufferedImage alternative = new BufferedImage( tileWidth, tileHeight, BufferedImage.TYPE_INT_RGB );
		
		final int[] targetPixels = new int[ tileWidth * tileHeight ];
		final ArrayImg< ARGBType, IntArray > targetTile = ArrayImgs.argbs( targetPixels, tileWidth, tileHeight );
		final BufferedImage target = new BufferedImage( tileWidth, tileHeight, BufferedImage.TYPE_INT_RGB );
		
		final BufferedImage sourceImage = new BufferedImage( tileWidth * 2, tileHeight * 2, BufferedImage.TYPE_INT_RGB );
		final Graphics2D g = sourceImage.createGraphics();
		final int[] sourcePixels = new int[ tileWidth * tileHeight * 4 ];
		final ArrayImg< ARGBType, IntArray > sourceTile = ArrayImgs.argbs( sourcePixels, tileWidth * 2, tileHeight * 2 );

		final Downsampler< ARGBType, ARGBDoubleType > downsampler =
				new Downsampler< ARGBType, ARGBDoubleType >(
						sourceTile,
						targetTile,
						new ARGBDoubleType(),
						new ARGBDoubleARGBConverter< ARGBDoubleType >(),
						new ARGBARGBDoubleConverter< ARGBDoubleType >() );
				
		/* orientation */
		final Interval viewInterval;
		switch ( orientation )
		{
		case XZ:
			viewInterval = new FinalInterval(
					new long[]{ sourceInterval.min( 0 ), sourceInterval.min( 2 ), sourceInterval.min( 1 ) },
					new long[]{ sourceInterval.max( 0 ), sourceInterval.max( 2 ), sourceInterval.max( 1 ) } );
			break;
		case ZY:
			viewInterval = new FinalInterval(
					new long[]{ sourceInterval.min( 2 ), sourceInterval.min( 1 ), sourceInterval.min( 0 ) },
					new long[]{ sourceInterval.max( 2 ), sourceInterval.max( 1 ), sourceInterval.max( 0 ) } );
			break;
		default:
			viewInterval = sourceInterval;
		}
		
		/* scale */
		for ( long l = minZ; l <= maxZ; ++l )
		{
			System.out.println( "z-index: " +  l );
			for (
				long w1 = ( long )Math.ceil( viewInterval.dimension( 0 ) * 0.5 ),
				h1 = ( long )Math.ceil( viewInterval.dimension( 1 ) * 0.5 ),
				s = 1;
				w1 > tileWidth && h1 > tileHeight;
				w1 = ( long )Math.ceil( w1 * 0.5 ),
				h1 = ( long )Math.ceil( h1 * 0.5 ),
				++s )
			{
				for ( long y = 0; y < h1; y += tileHeight )
				{
					final long yt = y / tileHeight;
					for ( long x = 0; x < w1; x += tileWidth )
					{
						final long xt = x / tileWidth;
						final Image imp1 = open(
								new StringBuffer( basePath ).
									append( "/" ).
									append( Tiler.tileName( tilePattern, s - 1, l, 2 * yt, 2 * xt ) ).
									append( "." ).
									append( format ).
									toString(),
								alternative,
								type );
						final Image imp2 = open(
								new StringBuffer( basePath ).
									append( "/" ).
									append( Tiler.tileName( tilePattern, s - 1, l, 2 * yt, 2 * xt + 1 ) ).
									append( "." ).
									append( format ).
									toString(),
								alternative,
								type );
						final Image imp3 = open(
								new StringBuffer( basePath ).
									append( "/" ).
									append( Tiler.tileName( tilePattern, s - 1, l, 2 * yt + 1, 2 * xt ) ).
									append( "." ).
									append( format ).
									toString(),
								alternative,
								type );
						final Image imp4 = open(
								new StringBuffer( basePath ).
									append( "/" ).
									append( Tiler.tileName( tilePattern, s - 1, l, 2 * yt + 1, 2 * xt + 1 ) ).
									append( "." ).
									append( format ).
									toString(),
								alternative,
								type );
						
						g.drawImage( imp1, 0, 0, null );
						g.drawImage( imp2, tileWidth, 0, null );
						g.drawImage( imp3, 0, tileHeight, null );
						g.drawImage( imp4, tileWidth, tileHeight, null );
						
						final PixelGrabber pg = new PixelGrabber( sourceImage, 0, 0, tileWidth * 2, tileHeight * 2, sourcePixels, 0, tileWidth * 2 );
						pg.grabPixels();
						
						downsampler.call();
						
						target.getRaster().setDataElements( 0, 0, tileWidth, tileHeight, targetPixels );
						final BufferedImage targetCopy = Tiler.draw( target, type );

						Tiler.writeTile(
								targetCopy,
								new StringBuffer( basePath ).
								append( "/" ).
								append( Tiler.tileName( tilePattern, s, l, yt, xt ) ).
								toString(),
								format,
								quality );
					}
				}
			}
		}
	}
	
	
	
	/**
	 * Generate scaled tiles from an existing scale level 0 tile stack.
	 * 
	 * @param sourceInterval the dimensions of the level 0 tile stack in
	 * 		original x,y,z orientation 
	 * @param orientation the orientation of the level 0 tile stack, required
	 * 		to transpose sourceInterval accordingly
	 * @param tileWidth
	 * @param tileHeight
	 * @param basePath base path of the source, this is also where the scaled
	 * 		tiles will be exported
	 * @param tilePattern the file name convention for tile coordinates without
	 * 		extension and base path, must contain "&lt;s&gt;","&lt;z&gt;",
	 * 		"&lt;r&gt;", "&lt;c&gt;".
	 * @param format file format, e.g. "jpg" or "png"
	 * @param quality quality for jpg-compression if format is "jpg"
	 * @param type the type of export tiles, e.g.
	 * 		{@link BufferedImage#TYPE_BYTE_GRAY}
	 * 
	 * @throws Exception
	 */
	final public static void scale(
			final Interval sourceInterval,
			final Tiler.Orientation orientation,
			final int tileWidth,
			final int tileHeight,
			final String basePath,
			final String tilePattern,
			final String format,
			final float quality,
			final int type ) throws Exception
	{
		scale(
				sourceInterval,
				orientation,
				tileWidth,
				tileHeight,
				sourceInterval.min( 2 ),
				sourceInterval.max( 2 ),
				basePath,
				tilePattern,
				format,
				quality,
				type );
	}
	
	
	final static public void scale( final Param p ) throws Exception
	{
		scale(
				p.sourceInterval,
				p.orientation,
				p.tileWidth,
				p.tileHeight,
				p.minZ,
				p.maxZ,
				p.basePath,
				p.tilePattern,
				p.format,
				p.quality,
				p.type );
	}

	
	final static public void main( final String... args ) throws Exception
	{
		scale( parseParameters() );
	}
}
