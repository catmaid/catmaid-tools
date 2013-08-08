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

import java.awt.image.BufferedImage;

import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealRandomAccessible;
import net.imglib2.interpolation.randomaccess.NearestNeighborInterpolatorFactory;
import net.imglib2.realtransform.RealViews;
import net.imglib2.realtransform.Scale3D;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.view.Views;

import org.catmaid.Tiler.Orientation;



/**
 * <p>A standalone command line application to export image tiles representing
 * scale level 0 of the tiled scale pyramids for the CATMAID interface from
 * existing CATMAID image stacks.  The program enables to crop a box of
 * interest from an existing CATMAID stack and to export only a subset of the
 * tile set.  It is thus easily possible (and desired) to parallelize the
 * export on a cluster.</p>
 * 
 * <p>The main utility of the program is to export resliced (xyz, xzy, zyx)
 * representations of existing CATMAID stacks.</p>
 * 
 * <p>Separation of scale level 0 tile generation and scaling is necessary to
 * enable the desired level of parallelization.  One would typically generate
 * the scale level 0 tile set parallelized in volumes that cover a moderate
 * number of source tiles.  Only after a <em>z</em>-section is fully exported,
 * it can be used to generate the scale pyramid.  I.e., scaling can be
 * parallelized by <em>z</em>-section but not within a <em>z</em>-section.</p>
 * 
 * @author Stephan Saalfeld <saalfeld@mpi-cbg.de>
 */
public class TileCATMAID
{
	static protected class Param
	{
		/* CATMAID source stack, representing an xyz-orientation */
		public String sourceBaseUrl;
		public long sourceWidth;
		public long sourceHeight;
		public long sourceDepth;
		public long sourceScaleLevel;
		public int sourceTileWidth;
		public int sourceTileHeight;
		public double sourceResXY;
		public double sourceResZ;
		
		/* export */
		/* source interval (crop area) in isotropic pixel coordinates */
		public Interval sourceInterval;
		public Tiler.Orientation orientation;
		public int tileWidth;
		public int tileHeight;
		public long minZ;
		public long maxZ;
		public long minR;
		public long maxR;
		public long minC;
		public long maxC;
		public String exportPath;
		public String tilePattern;
		public String format;
		public float quality;
		public int type;
	}
	
	static protected Param parseParameters()
	{
		final Param p = new Param();
		
		/* CATMAID source stack */
		p.sourceBaseUrl = System.getProperty( "sourceBaseUrl", "" );
		p.sourceWidth = Long.parseLong( System.getProperty( "sourceWidth", "0" ) );
		p.sourceHeight = Long.parseLong( System.getProperty( "sourceHeight", "0" ) );
		p.sourceDepth = Long.parseLong( System.getProperty( "sourceDepth", "0" ) );
		p.sourceScaleLevel = Long.parseLong( System.getProperty( "sourceScaleLevel", "0" ) );
		p.sourceTileWidth = Integer.parseInt( System.getProperty( "sourceTileWidth", "256" ) );
		p.sourceTileHeight = Integer.parseInt( System.getProperty( "sourceTileHeight", "256" ) );
		p.sourceResXY = Double.parseDouble( System.getProperty( "sourceResXY", "1.0" ) );
		p.sourceResZ = Double.parseDouble( System.getProperty( "sourceResZ", "1.0" ) );
		
		/* export */
		final long minX = Long.parseLong( System.getProperty( "minX", "0" ) );
		final long minY = Long.parseLong( System.getProperty( "minY", "0" ) );
		final long minZ = Long.parseLong( System.getProperty( "minZ", "0" ) );
		final long width = Long.parseLong( System.getProperty( "width", "0" ) );
		final long height = Long.parseLong( System.getProperty( "height", "0" ) );
		final long depth = Long.parseLong( System.getProperty( "depth", "0" ) );
		p.sourceInterval = new FinalInterval(
				new long[]{ minX, minY, minZ },
				new long[]{ minX + width - 1, minY + height - 1, minZ + depth - 1 } );
		final FinalInterval orientedSourceInterval;
		final String orientation = System.getProperty( "orientation", "xy" );
		if ( orientation.equalsIgnoreCase( "xz" ) )
		{
			p.orientation = Orientation.XZ;
			orientedSourceInterval = new FinalInterval(
					p.sourceInterval.dimension( 0 ),
					p.sourceInterval.dimension( 2 ),
					p.sourceInterval.dimension( 1 ) );
		}
		else if ( orientation.equalsIgnoreCase( "zy" ) )
		{
			p.orientation = Orientation.ZY;
			orientedSourceInterval = new FinalInterval(
					p.sourceInterval.dimension( 2 ),
					p.sourceInterval.dimension( 1 ),
					p.sourceInterval.dimension( 0 ) );
		}
		else
		{
			p.orientation = Orientation.XY;
			orientedSourceInterval = new FinalInterval( p.sourceInterval );
		}
		
		p.tileWidth = Integer.parseInt( System.getProperty( "tileWidth", "256" ) );
		p.tileHeight = Integer.parseInt( System.getProperty( "tileHeight", "256" ) );
		p.minZ = Long.parseLong( System.getProperty( "exportMinZ", "0" ) );
		p.maxZ = Long.parseLong( System.getProperty(
				"exportMaxZ",
				Long.toString( orientedSourceInterval.dimension( 2 ) - 1 ) ) );
		p.minR = Long.parseLong( System.getProperty( "exportMinR", "0" ) );
		p.maxR = Long.parseLong( System.getProperty(
				"exportMaxR",
				Long.toString( ( long )Math.ceil( ( double )orientedSourceInterval.dimension( 1 ) / ( double )p.tileHeight ) - 1 ) ) );
		p.minC = Long.parseLong( System.getProperty( "exportMinC", "0" ) );
		p.maxC = Long.parseLong( System.getProperty(
				"exportMaxC",
				Long.toString( ( long )Math.ceil( ( double )orientedSourceInterval.dimension( 0 ) / ( double )p.tileWidth ) - 1 ) ) );
		
		p.exportPath = System.getProperty( "exportBasePath", "" );
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
	
	/**
	 * Create a {@link Tiler} from a CATMAID stack.
	 * 
	 * @param baseUrl
	 * @param width	of scale level 0 in pixels
	 * @param height of scale level 0 in pixels
	 * @param depth	of scale level 0 in pixels
	 * @param s scale level to be used, using anything &gt;0 will create an
	 * 		accordingly scaled source stack 
	 * @param tileWidth
	 * @param tileHeight
	 * @param resXY <em>x,y</em>-resolution
	 * @param resZ <em>z</em>-resolution, what matters is only the ratio
	 * 		between <em>z</em>- and <em>x,y</em>-resolution to scale the source
	 * 		to isotropic resolution (if that is desired, you will want to do
	 * 		it when exporting re-sliced stacks, not when extracting at the
	 * 		original orientation).
	 *  
	 * @return
	 */
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
	
	
	
	
	final static public void main( final String[] args ) throws Exception
	{
		final Param p = parseParameters();
		fromCATMAID(
				p.sourceBaseUrl,
				p.sourceWidth,
				p.sourceHeight,
				p.sourceDepth,
				p.sourceScaleLevel,
				p.sourceTileWidth,
				p.sourceTileHeight,
				p.sourceResXY,
				p.sourceResZ ).tile(
						p.sourceInterval,
						p.orientation,
						p.tileWidth,
						p.tileHeight,
						p.minZ,
						p.maxZ,
						p.minR,
						p.maxR,
						p.minC,
						p.maxC,
						p.exportPath,
						p.tilePattern,
						p.format,
						p.quality,
						p.type );
	}
}
