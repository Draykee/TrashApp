package org.inventivetalent.trashapp.common;

import android.location.Location;
import android.util.Log;
import android.util.LongSparseArray;

import java.util.*;

public class OverpassResponse {

	public double        version;
	public String        generator;
	public List<Element> elements = new ArrayList<>();

	@Deprecated
	public List<Element> elementsSortedByDistanceFrom(final double lat, final double lon) {
		List<Element> sorted = new ArrayList<>(this.elements);
		Collections.sort(sorted, new Comparator<Element>() {
			@Override
			public int compare(Element o1, Element o2) {
				double d1 = (((lon - o1.lon) * (lon - o1.lon)) + ((lat - o1.lat) * (lat - o1.lat)));
				double d2 = (((lon - o2.lon) * (lon - o2.lon)) + ((lat - o2.lat) * (lat - o2.lat)));

				return (int) (d2 - d1);
			}
		});
		return sorted;
	}

	public static List<Element> elementsSortedByDistanceFrom(List<Element> elements, final Location location) {
		List<Element> sorted = new ArrayList<>(elements);
		Collections.sort(sorted, new Comparator<Element>() {
			@Override
			public int compare(Element o1, Element o2) {
				Float d1 = location.distanceTo(o1.toLocation());
				Float d2 = location.distanceTo(o2.toLocation());

				return d1.compareTo(d2);
			}
		});
		return sorted;
	}

	public static List<Element> convertElementsToPoints(List<Element> elements) {
		List<Element> pointElements = new ArrayList<>();

		LongSparseArray<Element> referencedNodes = new LongSparseArray<>();

		for (Element element : elements) {// filter nodes
			if ("node".equals(element.type)) {
				if (element.tags.isEmpty()) {// referenced element
					referencedNodes.put(element.id, element);
				} else {// standalone element
					pointElements.add(element);
				}
			} else if ("way".equals(element.type)) {
				// handle below
			}else{
				Log.w("OverpassResponse", "Unhandled element type: " + element.type);
			}
		}

		for (Element element : elements) {// handle ways
			if ("way".equals(element.type)) {
				List<Element> wayNodes = new ArrayList<>();
				for (long l : element.nodes) {
					Element node = referencedNodes.get(l);
					if (node == null) {
						Log.w("OverpassResponse", "Way references node #" + l + " but it's not in the response");
						continue;
					}
					wayNodes.add(node);
				}
				double[] wayCenter = Util.findPolygonCenter(wayNodes);

				element.lat = wayCenter[0];
				element.lon = wayCenter[1];

				pointElements.add(element);
			}
		}

		return pointElements;
	}

	public static class Element {

		public String type;
		public long   id;
		public double lat;
		public double lon;
		List<Long>          nodes = new ArrayList<>();
		Map<String, String> tags  = new HashMap<>();

		private Location location;

		public Location toLocation() {
			if (location == null) {
				location = new Location(String.valueOf(id));
				location.setLatitude(lat);
				location.setLongitude(lon);
			}
			return location;
		}

		@Override
		public String toString() {
			return "Element{" +
					"type='" + type + '\'' +
					", id=" + id +
					", lat=" + lat +
					", lon=" + lon +
					'}';
		}
	}

}
