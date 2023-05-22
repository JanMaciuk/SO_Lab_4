import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;

public class LRU {
    protected int physicalMemorySize;
    public ArrayList<Integer> requests;
    protected int currentRequestIndex = -1; // indeks aktualnie przetwarzanego requestu, inicjalizowany z -1, bo zwiększany przed użyciem.
    private final ArrayList<Integer> physicalMemory = new ArrayList<>();
    protected int errors = 0;
    protected double range;
    protected LinkedHashSet<Integer> workingSet = new LinkedHashSet<>();
    protected int WSS = 0;
    public LRU(int physicalMemorySize, ArrayList<Integer> requests) {
        this.physicalMemorySize = physicalMemorySize;
        this.requests = requests;
        this.range = Collections.max(requests) - Collections.min(requests); //Liczba stron używanych przez proces;
    }

    public void reset() {
        physicalMemory.clear();
        currentRequestIndex = -1;
        errors = 0;
        workingSet.clear();
        WSS = 0;
    }


    public void doLRU() {


            int indexOfRequestToReplace = requests.size()-1;
            currentRequestIndex++;
            if (!physicalMemory.contains(requests.get(currentRequestIndex))) { // jeżeli ramka jest w pamięci to nic nie robimy
                if (physicalMemory.size() < physicalMemorySize) {
                    physicalMemory.add(requests.get(currentRequestIndex)); // jeżeli mamy miejsce w pamięci to dodajemy bez zastępowania
                }
                else {
                    // szukam indeksu elementu z pamięci fizycznej, który najdłużej nie był używany
                    for (Integer memoryElement : physicalMemory) {
                        for (int i = currentRequestIndex; i >= 0; i--) {
                            if (requests.get(i).equals(memoryElement)) {
                                if (i < indexOfRequestToReplace) { indexOfRequestToReplace = i; }
                                break;
                            }
                        }
                    }
                    physicalMemory.set(physicalMemory.indexOf(requests.get(indexOfRequestToReplace)), requests.get(currentRequestIndex));
                }
                errors++;
            }
        workingSet.add(requests.get(currentRequestIndex)); // do modelu strefowego, WSS
    }

}
