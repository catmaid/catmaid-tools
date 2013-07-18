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

import interactive.remote.catmaid.CATMAIDRandomAccessibleInterval;

import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.awt.image.PixelGrabber;
import java.io.File;
import java.io.IOException;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.FileImageOutputStream;

import net.imglib2.Cursor;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealRandomAccessible;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.basictypeaccess.array.IntArray;
import net.imglib2.interpolation.randomaccess.NearestNeighborInterpolatorFactory;
import net.imglib2.realtransform.RealViews;
import net.imglib2.realtransform.Scale3D;
import net.imglib2.type.numeric.ARGBDoubleType;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.util.Intervals;
import net.imglib2.view.Views;
import render.volume.ARGBARGBDoubleConverter;
import render.volume.ARGBDoubleARGBConverter;

/**
 * 
 * 
 * @author Stephan Saalfeld <saalfeld@mpi-cbg.de>
 */
public class Tiler
{
	final static protected Toolkit toolkit = Toolkit.getDefaultToolkit();
	
	final protected RandomAccessibleInterval< ARGBType > source;
	
	public Tiler( final RandomAccessibleInterval< ARGBType > source )
	{
		this.source = source;
	}
	
	static public Tiler fromCATMAID(
			final String baseUrl,
			final long width,
			final long height,
			final long depth,
			final long s,
			final int tileWidth,
			final int tileHeight,
			final double resXY,
			final double resZ )
	{
		final CATMAIDRandomAccessibleInterval catmaidStack =
				new CATMAIDRandomAccessibleInterval(
						baseUrl,
						width,
						height,
						depth,
						s,
						tileWidth,
						tileHeight );

		/* scale and re-raster */
		final double scale = 1.0 / Math.pow( 2.0, s );
		final Scale3D scale3d = new Scale3D( 1, 1, resZ / resXY * scale );
		final RealRandomAccessible< ARGBType > interpolant =
				Views.interpolate( catmaidStack, new NearestNeighborInterpolatorFactory< ARGBType >() );
		final RandomAccessible< ARGBType > scaledInterpolant = RealViews.affine( interpolant, scale3d );
		final RandomAccessibleInterval< ARGBType > scaled =
				Views.interval(
						scaledInterpolant,
						new FinalInterval(
								( long )( scale * width ),
								( long )( scale * height ),
								( long )( scale * depth * resZ / resXY ) ) );
		
		return new Tiler( scaled );
	}
	
	
	static public enum Orientation
	{
		XY, XZ, ZY
	}
	
	
	/**
	 * Copy the contents of two
	 * {@link RandomAccessibleInterval RandomAccessibleIntervals} that have the
	 * same dimensions.
	 * 
	 * @param sourceTile
	 * @param targetTile
	 */
	final static protected void copyTile(
			final RandomAccessibleInterval< ARGBType > sourceTile,
			final RandomAccessibleInterval< ARGBType > targetTile )
	{
		final Cursor< ARGBType > src = Views.flatIterable( sourceTile ).cursor();
		final Cursor< ARGBType > dst = Views.flatIterable( targetTile ).cursor();

		while ( src.hasNext() )
			dst.next().set( src.next() );
	}
	

	/**
	 * Copy a {@link RandomAccessibleInterval} into another
	 * {@link RandomAccessibleInterval} of the same or larger size.
	 * If targetTile is larger than sourceTile then fill the rest with the
	 * passed background value.
	 * Copy order can be either row by row or column by column
	 * 
	 * @param sourceTile
	 * @param targetTile
	 * @param yx
	 * @param bg background value
	 */
	final static protected void copyTile(
			final RandomAccessibleInterval< ARGBType > sourceTile,
			final RandomAccessibleInterval< ARGBType > targetTile,
			final boolean yx,
			final ARGBType bg )
	{
		/* if sourceTile is smaller than targetTile, create the corresponding
		 * window in target and clear the rest */
		RandomAccessibleInterval< ARGBType > raiTarget;
		if ( Intervals.equalDimensions( sourceTile, targetTile ) )
			raiTarget = targetTile;
		else
		{
			/* clear */
			for ( final ARGBType p : Views.iterable( targetTile ) )
				p.set( bg );
			
			raiTarget = Views.interval( targetTile, sourceTile );			
		}
		
		final RandomAccessibleInterval< ARGBType > raiSource;
		if ( yx )
		{
			raiSource = Views.permute( sourceTile, 0, 1 );
			raiTarget = Views.permute( targetTile, 0, 1 );
		}
		else
			raiSource = sourceTile;
		
		copyTile( raiSource, raiTarget );
	}
	
	
	/**
	 * Replace the ctile coordinates in a pattern string.
	 * 
	 * @param template
	 * @param s scale-index (scale = 1/2<sup>s</sup>)
	 * @param z z-index
	 * @param r row
	 * @param c column
	 * @return
	 */
	final static protected String tileName(
			final String template,
			final long s,
			final long z,
			final long r,
			final long c )
	{
		return template.
				replace( "<s>", Long.toString( s ) ).
				replace( "<z>", Long.toString( z ) ).
				replace( "<r>", Long.toString( r ) ).
				replace( "<c>", Long.toString( c ) );
	}
	
	
	final static protected BufferedImage draw(
			final Image img,
			final int type )
	{
		final BufferedImage imgCopy = new BufferedImage( img.getWidth( null ), img.getHeight( null ), type );
		imgCopy.createGraphics().drawImage( img, 0, 0, null );
		return imgCopy;
	}
	

	final static protected void writeTile(
			final BufferedImage img,
			final String path,
			final String format,
			final float quality ) throws IOException
	{
		final String tilePath = new StringBuffer( path ).append( "." ).append( format ).toString();
		new File( tilePath ).getParentFile().mkdirs();
		final ImageWriter writer = ImageIO.getImageWritersByFormatName( format ).next();
		final FileImageOutputStream output = new FileImageOutputStream( new File( tilePath ) );
		writer.setOutput( output );
		if ( format.equalsIgnoreCase( "jpg" ) )
		{
			final ImageWriteParam param = writer.getDefaultWriteParam();
			param.setCompressionMode( ImageWriteParam.MODE_EXPLICIT );
			param.setCompressionQuality( quality );
			writer.write( null, new IIOImage( img.getRaster(), null, null ), param );
		}
		else
			writer.write( img );
		
		writer.dispose();
		output.close();
	}
	
	
	/**
	 * Generate a subset of a CATMAID tile stack of an {@link Interval} of the
	 * source {@link RandomAccessibleInterval}.  That is you can choose the
	 * window to be exported, and export only a subset thereof.
	 * 
	 * @param sourceInterval the interval of the source to be exported 
	 * @param orientation the export orientation
	 * @param tileWidth
	 * @param tileHeight
	 * @param minZ the first z-index to be exported
	 * @param maxZ the last z-index to be exported
	 * @param minR the first tile-row at scale-level 0 to be exported
	 * @param maxR the last tile-row at scale-level 0 to be exported
	 * @param minC the first tile-column at scale-level 0 to be exported
	 * @param maxC the last tile-column at scale-level 0 to be exported
	 * @param exportPath base path for export
	 * @param tilePattern the file name convention for tile coordinates without
	 * 			extension and base path, must contain "&lt;s&gt;","&lt;z&gt;",
	 * 			"&lt;r&gt;", "&lt;c&gt;".
	 * @param format
	 * @param quality
	 * @param type
	 * @throws IOException
	 */
	public void tile(
			final Interval sourceInterval,
			final Orientation orientation,
			final int tileWidth,
			final int tileHeight,
			final long minZ,
			final long maxZ,
			final long minR,
			final long maxR,
			final long minC,
			final long maxC,
			final String exportPath,
			final String tilePattern,
			final String format,
			final float quality,
			final int type ) throws IOException
	{
		/* orientation */
		final RandomAccessibleInterval< ARGBType > view;
		final Interval viewInterval;
		switch ( orientation )
		{
		case XZ:
			view = Views.permute( source, 1, 2 );
			viewInterval = new FinalInterval(
					new long[]{ sourceInterval.min( 0 ), sourceInterval.min( 2 ), sourceInterval.min( 1 ) },
					new long[]{ sourceInterval.max( 0 ), sourceInterval.max( 2 ), sourceInterval.max( 1 ) } );
			break;
		case ZY:
			view = Views.permute( source, 0, 2 );
			viewInterval = new FinalInterval(
					new long[]{ sourceInterval.min( 2 ), sourceInterval.min( 1 ), sourceInterval.min( 0 ) },
					new long[]{ sourceInterval.max( 2 ), sourceInterval.max( 1 ), sourceInterval.max( 0 ) } );
			break;
		default:
			view = source;
			viewInterval = sourceInterval;
		}
		
//		final long maxC = 
//		final long maxR = ( long ) Math.ceil( ( double ) viewInterval.dimension( 1 ) / ( double ) tileHeight ) - 1;
//		final long maxZ = viewInterval.dimension( 2 ) - 1;
//		
		final long[] min = new long[ 3 ];
		final long[] size = new long[ 3 ];
		size[ 2 ] = 1;
		
		final int[] tilePixels = new int[ tileWidth * tileHeight ];
		final ArrayImg< ARGBType, IntArray > tile = ArrayImgs.argbs( tilePixels, tileWidth, tileHeight );
		final BufferedImage img = new BufferedImage( tileWidth, tileHeight, BufferedImage.TYPE_INT_RGB );
		
		for ( long z = minZ; z <= maxZ; ++z )
		{
			min[ 2 ] = z + viewInterval.min( 2 );
			for ( long r = minR; r <= maxR; ++r )
			{
				min[ 1 ] = r * tileHeight + viewInterval.min( 1 );
				final long max1 = Math.min( viewInterval.max( 1 ), min[ 1 ] + tileHeight - 1 );
				size[ 1 ] = max1 - min[ 1 ] + 1;
				for ( long c = minC; c <= maxC; ++c )
				{
					min[ 0 ] = c * tileWidth + viewInterval.min( 0 );
					final long max0 = Math.min( viewInterval.max( 0 ), min[ 0 ] + tileWidth - 1 );
					size[ 0 ] = max0 - min[ 0 ] + 1;

					final RandomAccessibleInterval< ARGBType > sourceTile = Views.offsetInterval( view, min, size );

					copyTile( sourceTile, tile, orientation == Orientation.ZY, new ARGBType( 0 ) );
					img.getRaster().setDataElements( 0, 0, tileWidth, tileHeight, tilePixels );
					final BufferedImage imgCopy = draw( img, type );
					
					final String tilePath =
							new StringBuffer( exportPath ).
							append( "/" ).
							append( tileName( tilePattern, 0, z, r, c ) ).
							toString();
					
					writeTile( imgCopy, tilePath, format, quality );
//					writePngTile( img, sectionPath + "/" + r + "_" + c + "_0.png" );
				}
			}
		}
	}
	
	
	/**
	 * Generate a CATMAID tile stack of the source
	 * {@link RandomAccessibleInterval}.
	 * 
	 * @param sourceInterval the interval of the source to be exported 
	 * @param orientation the export orientation
	 * @param tileWidth
	 * @param tileHeight
	 * @param exportPath base path for export
	 * @param tilePattern the file name convention for tile coordinates without
	 * 			extension and base path, must contain "&lt;s&gt;","&lt;z&gt;",
	 * 			"&lt;r&gt;", "&lt;c&gt;".
	 * @param format
	 * @param quality
	 * @param type
	 * @throws IOException
	 */
	public void tile(
			final Interval sourceInterval,
			final Orientation orientation,
			final int tileWidth,
			final int tileHeight,
			final String exportPath,
			final String tilePattern,
			final String format,
			final float quality,
			final int type ) throws IOException
	{
		final long maxC;
		final long maxR;
		final long maxZ;
		
		switch ( orientation )
		{
		case XZ:
			maxC = ( long )Math.ceil( ( double )sourceInterval.dimension( 0 ) / ( double )tileWidth ) - 1;
			maxR = ( long )Math.ceil( ( double )sourceInterval.dimension( 2 ) / ( double )tileHeight ) - 1;
			maxZ = sourceInterval.dimension( 1 ) - 1;
			break;
		case ZY:
			maxC = ( long )Math.ceil( ( double )sourceInterval.dimension( 2 ) / ( double )tileWidth ) - 1;
			maxR = ( long )Math.ceil( ( double )sourceInterval.dimension( 1 ) / ( double )tileHeight ) - 1;
			maxZ = sourceInterval.dimension( 0 ) - 1;
			break;
		default:
			maxC = ( long )Math.ceil( ( double )sourceInterval.dimension( 0 ) / ( double )tileWidth ) - 1;
			maxR = ( long )Math.ceil( ( double )sourceInterval.dimension( 1 ) / ( double )tileHeight ) - 1;
			maxZ = sourceInterval.dimension( 2 ) - 1;
		}
		
		System.out.println( "maxZ:" + maxZ );
		System.out.println( "maxR:" + maxR );
		System.out.println( "maxC:" + maxC );
		
		tile(
				sourceInterval,
				orientation,
				tileWidth,
				tileHeight,
				0,
				maxZ,
				0,
				maxR,
				0,
				maxC,
				exportPath,
				tilePattern,
				format,
				quality,
				type );
	}
	
	
	/**
	 * Generate a CATMAID tile stack of the source
	 * {@link RandomAccessibleInterval}.
	 * 
	 * @param orientation the export orientation
	 * @param tileWidth
	 * @param tileHeight
	 * @param exportPath base path for export
	 * @param tilePattern the file name convention for tile coordinates without
	 * 			extension and base path, must contain "&lt;s&gt;","&lt;z&gt;",
	 * 			"&lt;r&gt;", "&lt;c&gt;".
	 * @param format
	 * @param quality
	 * @param type
	 * @throws IOException
	 */
	public void tile(
			final Orientation orientation,
			final int tileWidth,
			final int tileHeight,
			final String exportPath,
			final String tilePattern,
			final String format,
			final float quality,
			final int type ) throws IOException
	{
		tile(
				source,
				orientation,
				tileWidth,
				tileHeight,
				exportPath,
				tilePattern,
				format,
				quality,
				type );
	}
	
	
	/**
	 * Generate a subset of a CATMAID tile stack of an {@link Interval} of the
	 * source {@link RandomAccessibleInterval}.  That is you can choose the
	 * window to be exported, and export only a subset thereof.
	 * 
	 * @param orientation the export orientation
	 * @param tileWidth
	 * @param tileHeight
	 * @param minZ the first z-index to be exported
	 * @param maxZ the last z-index to be exported
	 * @param minR the first tile-row at scale-level 0 to be exported
	 * @param maxR the last tile-row at scale-level 0 to be exported
	 * @param minC the first tile-column at scale-level 0 to be exported
	 * @param maxC the last tile-column at scale-level 0 to be exported
	 * @param exportPath base path for export
	 * @param tilePattern the file name convention for tile coordinates without
	 * 			extension and base path, must contain "&lt;s&gt;","&lt;z&gt;",
	 * 			"&lt;r&gt;", "&lt;c&gt;".
	 * @param format
	 * @param quality
	 * @param type
	 * @throws IOException
	 */
	public void tile(
			final Orientation orientation,
			final int tileWidth,
			final int tileHeight,
			final long minZ,
			final long maxZ,
			final long minR,
			final long maxR,
			final long minC,
			final long maxC,
			final String exportPath,
			final String tilePattern,
			final String format,
			final float quality,
			final int type ) throws IOException
	{
		tile(
				source,
				orientation,
				tileWidth,
				tileHeight,
				minZ,
				maxZ,
				minR,
				maxR,
				minC,
				maxC,
				exportPath,
				tilePattern,
				format,
				quality,
				type );
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
	
	
	final public static void scale(
			final Interval sourceInterval,
			final Orientation orientation,
			final int tileWidth,
			final int tileHeight,
			final long minZ,
			final long maxZ,
			final String dirPath,
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
//		
		final Downsampler< ARGBType, ARGBDoubleType > downsampler =
				new Downsampler< ARGBType, ARGBDoubleType >(
						sourceTile,
						targetTile,
						new ARGBDoubleType(),
						new ARGBDoubleARGBConverter< ARGBDoubleType >(),
						new ARGBARGBDoubleConverter< ARGBDoubleType >() );
		
//		final Downsampler< ARGBType, ARGBType > downsampler =
//				Downsampler.create(
//						sourceTile,
//						targetTile );
		
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
								new StringBuffer( dirPath ).
									append( "/" ).
									append( tileName( tilePattern, s - 1, l, 2 * yt, 2 * xt ) ).
									append( "." ).
									append( format ).
									toString(),
								alternative,
								type );
						final Image imp2 = open(
								new StringBuffer( dirPath ).
									append( "/" ).
									append( tileName( tilePattern, s - 1, l, 2 * yt, 2 * xt + 1 ) ).
									append( "." ).
									append( format ).
									toString(),
								alternative,
								type );
						final Image imp3 = open(
								new StringBuffer( dirPath ).
									append( "/" ).
									append( tileName( tilePattern, s - 1, l, 2 * yt + 1, 2 * xt ) ).
									append( "." ).
									append( format ).
									toString(),
								alternative,
								type );
						final Image imp4 = open(
								new StringBuffer( dirPath ).
									append( "/" ).
									append( tileName( tilePattern, s - 1, l, 2 * yt + 1, 2 * xt + 1 ) ).
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
						final BufferedImage targetCopy = draw( target, type );

						writeTile(
								targetCopy,
								new StringBuffer( dirPath ).
								append( "/" ).
								append( tileName( tilePattern, s, l, yt, xt ) ).
								toString(),
								format,
								quality );
						
//						fileSaver = new FileSaver(impTile);
//						fileSaver.setJpegQuality(jpegQuality);
//						fileSaver.saveAsJpeg(dirPath + yt + "_" + xt + "_" + s + ".jpg");
					}
				}
			}
		}
	}
	
	final public static void scale(
			final Interval sourceInterval,
			final Orientation orientation,
			final int tileWidth,
			final int tileHeight,
			final String dirPath,
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
				dirPath,
				tilePattern,
				format,
				quality,
				type );
	}

	final static public void main( final String[] args ) throws Exception
	{
//		Tiler.fromCATMAID(
//				"http://incf.ini.uzh.ch/image-stack-fib/",
//				2048,
//				1536,
//				460,
//				0,
//				256,
//				256,
//				5,
//				15 ).tile(
//					new FinalInterval(
//							460 * 15 / 5,
//							1536,
//							2048 ),
//					Orientation.ZY,
//					256,
//					256,
//					"/home/saalfeld/tmp/catmaid/export-test/fib/zy",
//					0.85f );
		
//		Tiler.fromCATMAID(
//				"file:/home/saalfeld/tmp/catmaid/export-test/fib/aligned/xy/",
//				1987,
//				1441,
//				460,
//				0,
//				256,
//				256,
//				5.6,
//				11.2 ).tile(
//					new FinalInterval(
//							1987,
//							1441,
//							460 * 2 ),
//					Orientation.XZ,
//					256,
//					256,
//					"/home/saalfeld/tmp/catmaid/export-test/fib/aligned/xz",
//					"<z>/<r>_<c>_<s>",
//					"jpg",
//					0.85f,
//					BufferedImage.TYPE_BYTE_GRAY );
		
//		new ImageJ();
		
//		Tiler.fromCATMAID(
//				"file:/home/saalfeld/tmp/catmaid/export-test/fib/aligned/xy/",
//				1987,
//				1441,
//				460,
//				0,
//				256,
//				256,
//				5.6,
//				11.2 ).tile(
//						new FinalInterval(
//								new long[]{ 40, 0, 0 },
//								new long[]{ 42, 1440, 459 } ),
//					Orientation.ZY,
//					256,
//					256,
//					"/home/saalfeld/tmp/catmaid/export-test/fib/aligned/zy",
//					"<z>/<r>_<c>_<s>",
////					"<s>/<z>/<r>/<c>",
//					"jpg",
//					0.85f,
//					BufferedImage.TYPE_BYTE_GRAY );
//		
//		Tiler.scale(
//				new FinalInterval(
//						1987 / 2,
//						1441 / 2,
//						460 * 2 / 2 ),
//				Orientation.ZY,
//				256,
//				256,
//				40,
//				42,
//				"/home/saalfeld/tmp/catmaid/export-test/fib/aligned/zy",
//				"<z>/<r>_<c>_<s>",
//				"jpg",
//				0.85f,
//				BufferedImage.TYPE_BYTE_GRAY );
		
		Tiler.fromCATMAID(
				"http://braingraph1dev.cs.jhu.edu/view/bock11/",
				135200,
				119600,
				1233,
				0,
				256,
				256,
				3,
				3 /* 30 */ ).tile(
						new FinalInterval(
								new long[]{ 64000, 54000, 100 },
								new long[]{ 68000, 58000, 500 } ),
					Orientation.XY,
					256,
					256,
					"/home/saalfeld/tmp/catmaid/export-test/bock/xy",
					"<z>/<r>_<c>_<s>",
//					"<s>/<z>/<r>/<c>",
					"jpg",
					0.85f,
					BufferedImage.TYPE_BYTE_GRAY );
		
		Tiler.scale(
				new FinalInterval(
						4000,
						4000,
						400 ),
				Orientation.XY,
				256,
				256,
				40,
				42,
				"/home/saalfeld/tmp/catmaid/export-test/bock/xy",
				"<z>/<r>_<c>_<s>",
				"jpg",
				0.85f,
				BufferedImage.TYPE_BYTE_GRAY );
	}
}
