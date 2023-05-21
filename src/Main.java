import java.util.ArrayList;
import java.util.concurrent.ThreadLocalRandom;

public class Main {  //wyniki: https://docs.google.com/spreadsheets/d/1QiDLjMX_lORQf7Wva6eKuCVYIUOWK37q4Ch1MktIQm0
    static int liczbaRequestow = 1000; // na proces
    static int rozmiarPamieciWirtualnej = 2000; // Maksymalna na proces
    static int rozmiarPamieciFizycznej = 100; // Łączna pamięć, dla wszystkich procesów
    static int iloscProcesow = 5;
    static ArrayList<LRU> simList = new ArrayList<>();
    public static void main(String[] args) {


        generateProcesses();

        System.out.println(runEqual());
        simList.forEach(LRU::reset); // Resetuje wszystkie statystyki symulacji przed kolejnym uruchomieniem
        System.out.println(runProportional());
        simList.forEach(LRU::reset); // Resetuje wszystkie statystyki symulacji przed kolejnym uruchomieniem


    }

    public static void generateProcesses() {
        // Generuje losowe procesy: ich ciągi odwołań i symulacje.
        RequestGenerator requesty = new RequestGenerator();
        ArrayList<ArrayList<Integer>> processesRequests = new ArrayList<>();
        for (int i = 0; i < iloscProcesow; i++) {
            int processRange = ThreadLocalRandom.current().nextInt(rozmiarPamieciWirtualnej /4, rozmiarPamieciWirtualnej); // Losowy zakres każdego procesu
            processesRequests.add(requesty.generateRequests(processRange*i, processRange*(i+1), liczbaRequestow, i));
            simList.add(new LRU(rozmiarPamieciFizycznej/iloscProcesow, processesRequests.get(i))); // Domyślny przydział ramek, potem zmieniany przez algorytmy przydziału
        }
    }

    public static int runEqual() {
        //Równy podział ramek, procesy mają tyle samo ramek, o ile pamięć fizyczna dzieli się po równo.
        int assignedFrames = rozmiarPamieciFizycznej/iloscProcesow;
        int remainingFrames = rozmiarPamieciFizycznej;
        int errors = 0;
        for (int j = 0; j < liczbaRequestow; j++) { // Dla każdego procesu wykonuję jeden request, tyle razy ile jest procesów

            for (LRU sim : simList) {
                if (j==0) { // Na pierwszej iteracji przydzielam ramki
                    sim.physicalMemorySize = assignedFrames;
                    remainingFrames -= assignedFrames;
                }
                else if (remainingFrames > 0) { // Przydzielam procesom pozostałe ramki (zostaną jeżeli jest reszta z dzielenia)
                    sim.physicalMemorySize++;
                    remainingFrames--;
                }

                sim.doLRU(); // Wykonuje request

                if (j == liczbaRequestow-1) { // Na ostatniej iteracji zliczam błędy
                    errors += sim.errors;
                }
            }
        }
        return errors;
    }

    public static int runProportional() {
        // Przydział proporcjonalny: procesy mają przydzieloną ilość ramek proporcjonalną do ich ilości stron
        int assignedFrames;
        int remainingFrames = rozmiarPamieciFizycznej;
        int totalFramesUsed = 0;
        int errors = 0;
        for (int j = 0; j < liczbaRequestow; j++) { // Dla każdego procesu wykonuję jeden request, tyle razy ile jest procesów

            for (LRU sim : simList) {
                if (j==0) { // Na pierwszej iteracji zliczam łączną liczbę ramek
                    totalFramesUsed += sim.range;

                }
                else if (j==1) { // Przydzielam procesom ramki proporcjonalnie do ich zakresu
                    int percentage = (int) ( (double) (sim.range/totalFramesUsed*100));
                    assignedFrames = Math.max(rozmiarPamieciFizycznej/100*percentage,1);
                    sim.physicalMemorySize = assignedFrames;
                    remainingFrames -= assignedFrames;
                }
                else if (remainingFrames > 0) { // Przydzielam procesom pozostałe ramki
                    //System.out.println(remainingFrames);
                    sim.physicalMemorySize++;
                    remainingFrames--;

                }

                sim.doLRU(); // Wykonuje request

                if (j == liczbaRequestow-1) { // Na ostatniej iteracji zliczam błędy
                    errors += sim.errors;
                    System.out.println(sim.physicalMemorySize);
                }
            }
        }
        return errors;
    }

    public static ArrayList<Integer> sumRequests(ArrayList<ArrayList<Integer>> processesRequests) {
        ArrayList<Integer> sum = new ArrayList<>();
        for (int i = 0; i < liczbaRequestow; i++) {
            for (ArrayList<Integer> requests : processesRequests) {
                sum.add(requests.get(i));
            }
        }

        return sum;
    }




}