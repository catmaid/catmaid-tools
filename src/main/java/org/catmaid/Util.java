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

import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.FileImageOutputStream;

/**
 * 
 *
 * @author Stephan Saalfeld <saalfelds@janelia.hhmi.org>
 */
public class Util
{
	final static public String tilePath(
			final String tileFormat,
			final int scaleLevel,
			final double scale,
			final long x,
			final long y,
			final long z,
			final int tileWidth,
			final int tileHeight,
			final long row,
			final long column )
	{
		return String.format( tileFormat, scaleLevel, scale, x, y, z, tileWidth, tileHeight, row, column );
	}
	
	final static public BufferedImage draw(
			final Image img,
			final int type )
	{
		final BufferedImage imgCopy = new BufferedImage( img.getWidth( null ), img.getHeight( null ), type );
		imgCopy.createGraphics().drawImage( img, 0, 0, null );
		return imgCopy;
	}
	
	final static public void writeTile(
			final BufferedImage img,
			final String path,
			final String format,
			final float quality ) throws IOException
	{
		new File( path ).getParentFile().mkdirs();
		final ImageWriter writer = ImageIO.getImageWritersByFormatName( format ).next();
		final FileImageOutputStream output = new FileImageOutputStream( new File( path ) );
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
}
