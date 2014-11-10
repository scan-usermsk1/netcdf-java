package ucar.coord;

import net.jcip.annotations.Immutable;
import ucar.ma2.Section;
import ucar.nc2.util.Indent;

import java.util.*;

/**
 * N dimensional coordinates.
 *
 * @author caron
 * @since 11/27/13
 */
@Immutable
public class CoordinateND<T> {

  private final List<Coordinate> coordinates; // result is orthogonal coordinates
  private final SparseArray<T> sa;            // indexes refer to coordinates

  public CoordinateND( List<Coordinate> coordinates, SparseArray<T> sa) {
    this.coordinates = Collections.unmodifiableList(coordinates);
    this.sa = sa;
  }

  public List<Coordinate> getCoordinates() {
    return coordinates;
  }

  public int getNCoordinates() {
    return coordinates.size();
  }

  public SparseArray<T> getSparseArray() {
    return sa;
  }

  /* public void showInfo(List<T> records, Formatter info) {
    if (sa == null) buildSparseArray(records, info);

    for (Coordinate coord : coordinates)
      coord.showInfo(info, new Indent(2));
    info.format("%n%n");
    if (sa != null) sa.showInfo(info, null);
    info.format("%n");
  }  */

  public void showInfo(Formatter info, Counter all) {
    for (Coordinate coord : coordinates)
       coord.showInfo(info, new Indent(2));

    if (sa != null) sa.showInfo(info, all);
  }

  ////////////////////

  /* public CoordinateND( List<Coordinate> coordinates) {
    this.coordinates = coordinates;
    // make the new sparse array object
    int[] sizeArray = new int[coordinates.size()];
    for (int i = 0; i < coordinates.size(); i++)
      sizeArray[i] = coordinates.get(i).getSize();
    sa = new SparseArray<>(sizeArray);
  } */

  public static class Builder<T> {
    private List<CoordinateBuilder<T>> builders = new ArrayList<>();
    private List<Coordinate> coordb = new ArrayList<>();

    public Builder() {
      builders = new ArrayList<>();
    }

    public void addBuilder(CoordinateBuilder<T> builder) {
      builders.add(builder);
    }

    public void addRecord(T gr) {
      for (CoordinateBuilder<T> builder : builders)
        builder.addRecord(gr);
    }

    public CoordinateND<T> finish(List<T> records, Formatter info) {
      for (CoordinateBuilder builder : builders) {
        Coordinate coord = builder.finish();
        // if (coord.getType() == Coordinate.Type.time2D)
        //   coordinates.add(((CoordinateTime2D) coord).getRuntimeCoordinate());
        coordb.add(coord);
      }

      SparseArray<T> sa = buildSparseArray(records, info);
      return new CoordinateND<T>(coordb, sa);
    }

    public SparseArray<T> buildSparseArray(List<T> records, Formatter info) {
      int[] sizeArray = new int[coordb.size()];
      for (int i = 0; i < coordb.size(); i++)
        sizeArray[i] = coordb.get(i).getSize();
      SparseArray.Builder<T> saBuilder = new SparseArray.Builder<>(sizeArray);

      int[] index = new int[coordb.size()];
      for (T gr : records) {
        int count = 0;
        for (CoordinateBuilder<T> builder : builders) {
          index[count++] = builder.getIndex(gr);
        }

        saBuilder.add(gr, info, index);
      }

      return saBuilder.finish();
    }

    /**
     * Reindex the sparse array, based on the new Coordinates.
     * Do this by running all the Records through the Coordinates, assigning each to a possible new spot in the sparse array.
     *
     * @param prev must have same list of Coordinates, with possibly additional values.
     */
    public CoordinateND<T> reindex(List<Coordinate> newCoords, CoordinateND<T> prev) {
      SparseArray<T> prevSA = prev.getSparseArray();
      List<Coordinate> prevCoords = prev.getCoordinates();

            // make a working sparse array with new shape
      int[] sizeArray = new int[newCoords.size()];
      for (int i = 0; i < newCoords.size(); i++) {
        sizeArray[i] = newCoords.get(i).getSize();
      }
      SparseArray.Builder<T> workingSAbuilder = new SparseArray.Builder<>(sizeArray);

      // for each coordinate, calculate the map of oldIndex -> newIndex
      List<IndexMap> indexMaps = new ArrayList<>();
      int count = 0;
      for (Coordinate curr : newCoords)
        indexMaps.add(new IndexMap(curr, prevCoords.get(count++)));

      int[] currIndex = new int[newCoords.size()];
      int[] prevIndex = new int[newCoords.size()];
      int[] track = new int[SparseArray.calcTotalSize(sizeArray)];

      // iterate through the contents of the prev track array
      Section section = new Section(prevSA.getShape());
      Section.Iterator iter = section.getIterator(prevSA.getShape());
      while (iter.hasNext()) {
        int oldTrackIdx = iter.next(prevIndex); // return both the index (1D) and index[n]
        int oldTrackValue = prevSA.getTrack(oldTrackIdx);
        if (oldTrackValue == 0) continue; // skip missing values

        // calculate position in the current track array, and store the value there
        int coordIdx = 0;
        for (IndexMap indexMap : indexMaps) {
          currIndex[coordIdx] = indexMap.map(prevIndex[coordIdx]);
          coordIdx++;
        }
        int trackIdx = workingSAbuilder.calcIndex(currIndex);
        track[trackIdx] = oldTrackValue;
      }

      // now that we have the track, make the real SA
      SparseArray<T> newSA = new SparseArray<>(sizeArray, track, prevSA.getContent(), prevSA.getNdups());  // content (list of records) is the same
      return new CoordinateND<>(newCoords, newSA);                                      // reindexed result
    }

    private static class IndexMap {
      boolean identity = true;
      int[] indexMap;

      IndexMap(Coordinate curr, Coordinate prev) {
        identity = curr.equals(prev);
        if (identity) return;

        assert curr.getType() == prev.getType();

        int count = 0;
        Map<Object, Integer> currValMap = new HashMap<>();
        for (Object val : curr.getValues()) currValMap.put(val, count++);

        count = 0;
        indexMap = new int[prev.getSize()];
        for (Object val : prev.getValues())
          indexMap[count++] = currValMap.get(val); // where does this value fit in the curr coordinates?
      }

      int map(int oldIndex) {
        if (identity) return oldIndex;
        return indexMap[oldIndex];
      }

    }
  }


}
