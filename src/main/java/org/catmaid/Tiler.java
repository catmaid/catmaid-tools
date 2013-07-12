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

import java.awt.Image;
import java.awt.image.BufferedImage;
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
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.util.Intervals;
import net.imglib2.view.Views;

/**
 * 
 * 
 * @author Stephan Saalfeld <saalfeld@mpi-cbg.de>
 */
public class Tiler
{
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
	 * @param tilePattern
	 * @param s scale-index (scale = 1/2<sup>s</sup>)
	 * @param z z-index
	 * @param r row
	 * @param c column
	 * @return
	 */
	final static protected String tileName(
			final String tilePattern,
			final long s,
			final long z,
			final long r,
			final long c )
	{
		return tilePattern.
				replace( "<s>", Long.toString( s ) ).
				replace( "<z>", Long.toString( z ) ).
				replace( "<r>", Long.toString( r ) ).
				replace( "<c>", Long.toString( c ) );
	}
	
	
	final static protected BufferedImage draw(
			final Image img,
			final int type )
	{
		final BufferedImage imgCopy = new BufferedImage( img.getWidth( null ), img.getHeight( null ), BufferedImage.TYPE_BYTE_GRAY );
		imgCopy.getGraphics().drawImage( img, 0, 0, null );
		return imgCopy;
	}
	

	final static protected void writeTile(
			final BufferedImage img,
			final String path,
			final String format,
			final float quality ) throws IOException
	{
		final String tilePath = path.concat( "." ).concat( format ).toString();
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
	 * Generate a CATMAID tile stack of the source
	 * {@link RandomAccessibleInterval}.
	 * 
	 * @param sourceInterval
	 * @param orientation
	 * @param tileWidth
	 * @param tileHeight
	 * @param exportPath
	 * @param tilePattern
	 * @param format
	 * @param quality
	 * @param type
	 * @throws IOException
	 */
	final public void tile(
			final Interval sourceInterval,
			final Orientation orientation,
			final int tileWidth,
			final int tileHeight,
			final long minZ,
			final long minR,
			final long minC,
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
		
		final long cols = ( long ) Math.ceil( ( double ) viewInterval.dimension( 0 ) / ( double ) tileWidth );
		final long rows = ( long ) Math.ceil( ( double ) viewInterval.dimension( 1 ) / ( double ) tileHeight );

		final long[] min = new long[ 3 ];
		final long[] size = new long[ 3 ];
		size[ 2 ] = 1;
		
		final int[] tilePixels = new int[ tileWidth * tileHeight ];
		final ArrayImg< ARGBType, IntArray > tile = ArrayImgs.argbs( tilePixels, tileWidth, tileHeight );
		final BufferedImage img = new BufferedImage( tileWidth, tileHeight, BufferedImage.TYPE_INT_ARGB );
		
		for ( long z = 0; z < viewInterval.dimension( 2 ); ++z )
		{
			min[ 2 ] = z + viewInterval.min( 2 );
			for ( long r = 0; r < rows; ++r )
			{
				min[ 1 ] = r * tileHeight + viewInterval.min( 1 );
				final long max1 = Math.min( viewInterval.max( 1 ), min[ 1 ] + tileHeight - 1 );
				size[ 1 ] = max1 - min[ 1 ] + 1;
				for ( long c = 0; c < cols; ++c )
				{
					min[ 0 ] = c * tileWidth + viewInterval.min( 0 );
					final long max0 = Math.min( viewInterval.max( 0 ), min[ 0 ] + tileWidth - 1 );
					size[ 0 ] = max0 - min[ 0 ] + 1;

					final RandomAccessibleInterval< ARGBType > sourceTile = Views.offsetInterval( view, min, size );

					copyTile( sourceTile, tile, orientation == Orientation.ZY, new ARGBType( 0 ) );
					img.getRaster().setDataElements( 0, 0, tileWidth, tileHeight, tilePixels );
					final BufferedImage imgCopy = draw( img, type );
					
					final String tilePath =
							exportPath.
							concat( "/" ).
							concat( tileName( tilePattern, 0, z + minZ , r + minR, c + minC ) ).
							toString();
					
					writeTile( imgCopy, tilePath, format, quality );
//					writePngTile( img, sectionPath + "/" + r + "_" + c + "_0.png" );
				}
			}
		}
	}

	final static public void main( final String[] args ) throws IOException
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
		
		Tiler.fromCATMAID(
				"file:/home/saalfeld/tmp/catmaid/export-test/fib/aligned/xy/",
				1987,
				1441,
				460,
				0,
				256,
				256,
				5.6,
				11.2 ).tile(
					new FinalInterval(
							1987,
							1441,
							460 * 2 ),
					Orientation.ZY,
					256,
					256,
					0,
					0,
					0,
					"/home/saalfeld/tmp/catmaid/export-test/fib/aligned/zy",
					"<z>/<r>_<c>_<s>",
//					"<s>/<z>/<r>/<c>",
					"jpg",
					0.85f,
					BufferedImage.TYPE_BYTE_GRAY );
	}
}
