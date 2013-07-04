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

import interactive.catmaid.CATMAIDRandomAccessibleInterval;

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
import net.imglib2.view.Views;

/**
 * 
 * 
 * @author Stephan Saalfeld <saalfeld@mpi-cbg.de>
 */
public class StackReslicer
{
	final protected RandomAccessibleInterval< ARGBType > source;
	protected int[] tilePixels;
	protected ArrayImg< ARGBType, IntArray > tile;
	protected RandomAccessibleInterval< ARGBType > tileView;
	protected BufferedImage img; 
	
	public StackReslicer( final RandomAccessibleInterval< ARGBType > source )
	{
		this.source = source;
	}
	
	static public StackReslicer fromCATMAID(
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
		final CATMAIDRandomAccessibleInterval catmaidStack = new CATMAIDRandomAccessibleInterval( baseUrl, width, height, depth, s, tileWidth, tileHeight );

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
		
		return new StackReslicer( scaled );
	}
	
	static public enum Orientation
	{
		XY, XZ, ZY
	}

	final protected void copyTile( final RandomAccessibleInterval< ARGBType > sourceTile )
	{
		final Cursor< ARGBType > src = Views.flatIterable( sourceTile ).cursor();
		final Cursor< ARGBType > dst = Views.flatIterable( tile ).cursor();

		while ( src.hasNext() )
			dst.next().set( src.next() );
	}

	final protected void writeJpegTile( final String path, final float quality ) throws IOException
	{
		final BufferedImage imgCopy = new BufferedImage( ( int )tile.dimension( 0 ), ( int )tile.dimension( 1 ), BufferedImage.TYPE_BYTE_GRAY );
		imgCopy.getGraphics().drawImage( img, 0, 0, null );
		final ImageWriter writer = ImageIO.getImageWritersByFormatName( "jpg" ).next();
		final FileImageOutputStream output = new FileImageOutputStream( new File( path ) );
		final ImageWriteParam param = writer.getDefaultWriteParam();
		param.setCompressionMode( ImageWriteParam.MODE_EXPLICIT );
		param.setCompressionQuality( quality );
		writer.setOutput( output );
		writer.write( null, new IIOImage( imgCopy.getRaster(), null, null ), param );
		writer.dispose();
		output.close();
	}

	
	final public void tile(
			final Interval interval,
			final Orientation orientation,
			final int tileWidth,
			final int tileHeight,
			final String exportPath,
			final float quality ) throws IOException
	{
		/* orientation */
		final RandomAccessibleInterval< ARGBType > view;
		switch ( orientation )
		{
		case XZ:
			view = Views.permute( source, 1, 2 );
			break;
		case ZY:
			view = Views.permute( source, 0, 2 );
			break;
		default:
			view = source;
		}
		
		final long cols = ( long ) Math.ceil( ( double ) interval.dimension( 0 ) / ( double ) tileWidth );
		final long rows = ( long ) Math.ceil( ( double ) interval.dimension( 1 ) / ( double ) tileHeight );

		final long[] min = new long[ 3 ];
		final long[] max = new long[ 3 ];
		
		tilePixels = new int[ tileWidth * tileHeight ];
		tile = ArrayImgs.argbs( tilePixels, tileWidth, tileHeight );
		img = new BufferedImage( tileWidth, tileHeight, BufferedImage.TYPE_INT_ARGB );
		
		for ( long z = interval.min( 2 ); z <= interval.max( 2 ); ++z )
		{
			min[ 2 ] = max[ 2 ] = z;
			final String sectionPath = exportPath + "/" + z;
			new File( sectionPath ).mkdirs();
			for ( long r = 0; r < rows; ++r )
			{
				min[ 1 ] = r * tileHeight + interval.min( 1 );
				max[ 1 ] = min[ 1 ] + tileHeight - 1;
				for ( long c = 0; c < cols; ++c )
				{
					min[ 0 ] = c * tileWidth + interval.min( 0 );
					max[ 0 ] = min[ 0 ] + tileWidth - 1;

					final RandomAccessibleInterval< ARGBType > sourceTile = Views.interval( view, min, max );

					copyTile( sourceTile );
					img.getRaster().setDataElements( 0, 0, tileWidth, tileHeight, tilePixels );

					writeJpegTile( sectionPath + "/" + r + "_" + c + "_0.jpg", quality );
				}
			}
		}
	}

	final static public void main( final String[] args ) throws IOException
	{
//		StackReslicer.fromCATMAID(
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
		
		StackReslicer.fromCATMAID(
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
							460 * 2,
							1441 ),
					Orientation.XZ,
					256,
					256,
					"/home/saalfeld/tmp/catmaid/export-test/fib/aligned/xz",
					0.85f );
	}
}
