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

import java.util.ArrayList;
import java.util.concurrent.Callable;

import net.imglib2.Cursor;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.converter.Converter;
import net.imglib2.converter.TypeIdentity;
import net.imglib2.img.Img;
import net.imglib2.img.ImgFactory;
import net.imglib2.type.numeric.NumericType;
import net.imglib2.view.SubsampleIntervalView;
import net.imglib2.view.Views;

/**
 * 
 *
 * @author Stephan Saalfeld <saalfeld@mpi-cbg.de>
 */
public class GenericDownsampler< T extends NumericType< T >, A extends NumericType< A > > implements Callable< RandomAccessibleInterval< T > >
{
	final protected RandomAccessibleInterval< T > source;
	final protected RandomAccessibleInterval< T > target;
	final protected A accumulator, variable;
	final protected Converter< A, T > at;
	final protected Converter< T, A > ta;
	final protected long[] sizeMinusOne;
	final protected double div;
	
	final static protected long[] sizeMinusOne( final Interval source )
	{
		final long[] s = new long[ source.numDimensions() ];
		for ( int d = 0; d < s.length; ++d )
			s[ d ] = source.dimension( d ) - 1;
		return s;
	}
	
	final static protected long[] maxMinusOne( final Interval source )
	{
		final long[] s = new long[ source.numDimensions() ];
		for ( int d = 0; d < s.length; ++d )
			s[ d ] = source.max( d ) - 1;
		return s;
	}
	
	final static protected long[] evenSize( final Interval source )
	{
		final long[] s = new long[ source.numDimensions() ];
		for ( int d = 0; d < s.length; ++d )
			s[ d ] = source.dimension( d ) & 0xfffffffffffffffeL;  // / 2
		return s;
	}
	
	final static protected long[] halfSize( final Interval source )
	{
		final long[] s = new long[ source.numDimensions() ];
		for ( int d = 0; d < s.length; ++d )
			s[ d ] = source.dimension( d ) >> 1;  // / 2
		return s;
	}
	
	public GenericDownsampler(
			final RandomAccessibleInterval< T > source,
			final RandomAccessibleInterval< T > target,
			final A accumulator,
			final Converter< A, T > at,
			final Converter< T, A > ta )
	{
		this.source = Views.offsetInterval( source, new long[ source.numDimensions() ], evenSize( source ) );
		this.target = target;
		this.accumulator = accumulator;
		variable = accumulator.createVariable();
		this.at = at;
		this.ta = ta;
		
		sizeMinusOne = sizeMinusOne( source );
		div = 1.0 / ( 1L << sizeMinusOne.length );
	}
	
	public GenericDownsampler(
			final RandomAccessibleInterval< T > source,
			final ImgFactory< T > targetFactory,
			final A accumulator,
			final Converter< A, T > at,
			final Converter< T, A > ta )
	{
		this(
				source,
				targetFactory.create( halfSize( source ), source.randomAccess().get().createVariable() ),
				accumulator,
				at,
				ta );
	}
	
	public GenericDownsampler(
			final Img< T > source,
			final A accumulator,
			final Converter< A, T > at,
			final Converter< T, A > ta )
	{
		this(
				source,
				source.factory().create( halfSize( source ), source.randomAccess().get().createVariable() ),
				accumulator,
				at,
				ta );
	}
	
	
	static public < T extends NumericType< T > > GenericDownsampler< T, T > create(
			final RandomAccessibleInterval< T > source,
			final RandomAccessibleInterval< T > target )
	{
		final TypeIdentity< T > atta = new TypeIdentity< T >();
		return new GenericDownsampler< T, T >(
				source,
				target,
				source.randomAccess().get().createVariable(),
				atta,
				atta );
	}
	
	
	static public < T extends NumericType< T > > GenericDownsampler< T, T > create(
			final RandomAccessibleInterval< T > source,
			final ImgFactory< T > targetFactory )
	{
		final TypeIdentity< T > atta = new TypeIdentity< T >();
		final T firstElement = source.randomAccess().get();
		return new GenericDownsampler< T, T >(
				source,
				targetFactory.create( halfSize( source ), firstElement.createVariable() ),
				firstElement.createVariable(),
				atta,
				atta );
	}
	
	static public < T extends NumericType< T > > GenericDownsampler< T, T > create(
			final Img< T > source )
	{
		final TypeIdentity< T > atta = new TypeIdentity< T >();
		final T firstElement = source.randomAccess().get();
		return new GenericDownsampler< T, T >(
				source,
				source.factory().create( halfSize( source ), firstElement.createVariable() ),
				firstElement.createVariable(),
				atta,
				atta );
	}
	
	
	protected void average( final ArrayList< Cursor< T > > cursors )
	{
		final Cursor< T > targetCursor = Views.flatIterable( target ).cursor();
		while ( targetCursor.hasNext() )
		{
			accumulator.setZero();
			for ( final Cursor< T > c : cursors )
			{
				ta.convert( c.next(), variable );
				accumulator.add( variable );
			}
			accumulator.mul( div );
			at.convert( accumulator, targetCursor.next() );
		}
	}
	
	
	public RandomAccessibleInterval< T > call() throws Exception
	{
		final long[] min = new long[ sizeMinusOne.length ];
		final long[] max = new long[ sizeMinusOne.length ];
		final ArrayList< RandomAccessibleInterval< T > > views = new ArrayList< RandomAccessibleInterval< T > >( 1 << sizeMinusOne.length );
		final ArrayList< Cursor< T > > cursors = new ArrayList< Cursor< T > >( views.size() );
		
		views.add( Views.interval( source, min, sizeMinusOne ) );
		
		/* shift */
		for ( int d = 0; d < sizeMinusOne.length; ++d )
		{
			final int l = views.size();
			for ( int e = 0; e < l; ++e )
			{
				final RandomAccessibleInterval< T > view = views.get( e ); 
				view.min( min );
				view.max( max );
				min[ d ] = 1;
				max[ d ] += 1;
				views.add( Views.interval( source, min, max ) );
			}
		}
		
		/* downsample and cursors */
		for ( int d = 0; d < views.size(); ++d )
		{
			final SubsampleIntervalView< T > v = Views.subsample( views.get( d ), 2 );
			views.set( d, v );
			cursors.add(  Views.flatIterable( v ).cursor() );
		}
		
		/* average */
		average( cursors );
		
		return target;
	}
}
