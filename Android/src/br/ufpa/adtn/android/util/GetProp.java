/**
 * Amazon-DTN - Lightweight Delay Tolerant Networking Implementation
 * Copyright (C) 2013  Dórian C. Langbeck
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package br.ufpa.adtn.android.util;

import java.io.BufferedReader;
import java.io.InputStreamReader;

public final class GetProp {
	
	public static String get(String prop) {
		try {
			final Process proc = Runtime.getRuntime().exec(new String[] {
					"/system/bin/getprop",
					prop
			});
			
			final BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()));
			final String line = reader.readLine();
			reader.close();
			
			return line;
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
	
	private GetProp() { }
}
