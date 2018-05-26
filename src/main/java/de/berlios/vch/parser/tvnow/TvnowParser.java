package de.berlios.vch.parser.tvnow;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.felix.ipojo.annotations.Component;
import org.apache.felix.ipojo.annotations.Provides;
import org.apache.felix.ipojo.annotations.Requires;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.osgi.service.log.LogService;

import de.berlios.vch.http.client.HttpUtils;
import de.berlios.vch.parser.IOverviewPage;
import de.berlios.vch.parser.IVideoPage;
import de.berlios.vch.parser.IWebPage;
import de.berlios.vch.parser.IWebParser;
import de.berlios.vch.parser.OverviewPage;
import de.berlios.vch.parser.VideoPage;
import de.berlios.vch.parser.WebPageTitleComparator;

@Component
@Provides
public class TvnowParser implements IWebParser {

    public static final String ID = TvnowParser.class.getName();

    protected final String API_URL = "https://api.tvnow.de/v3";
    protected final String PROGRAMS_URL = API_URL + "/formats?fields=id,title,station,title,titleGroup,seoUrl,icon,hasFreeEpisodes,hasPayEpisodes,categoryId,searchAliasName,genres";
    protected final String BASE_URL = "https://www.tvnow.de";
    public static final String CHARSET = "utf-8";



    @Requires
    private LogService logger;

    @Override
    public IOverviewPage getRoot() throws Exception {
        OverviewPage root = new OverviewPage();
        root.setParser(ID);
        root.setTitle(getTitle());
        root.setUri(new URI("vchpage://localhost/" + getId()));

        Map<String, Map<String, List<Program>>> programTree = loadProgramStructure();
        for (Entry<String, Map<String, List<Program>>> entry : programTree.entrySet()) {
            String stationName = entry.getKey();
            OverviewPage station = new OverviewPage();
            station.setParser(getId());
            station.setUri(new URI("tvnow://station/" + stationName));
            station.setTitle(stationName);
            root.getPages().add(station);
            Map<String, List<Program>> letters = entry.getValue();
            for (Entry<String, List<Program>> letterEntry : letters.entrySet()) {
                String letter = letterEntry.getKey();
                OverviewPage letterPage = new OverviewPage();
                letterPage.setParser(getId());
                letterPage.setUri(new URI(stationName + "://letter/" + letter));
                letterPage.setTitle(letter);
                station.getPages().add(letterPage);
                Collections.sort(station.getPages(), new WebPageTitleComparator());

                List<Program> programs = letterEntry.getValue();
                Collections.sort(programs, new Comparator<Program>() {
                    @Override
                    public int compare(Program o1, Program o2) {
                        return o1.title.compareTo(o2.title);
                    }
                });
                for (Program program : programs) {
                    OverviewPage programPage = new OverviewPage();
                    programPage.setParser(getId());
                    programPage.setUri(new URI(stationName + "://program/" + URLEncoder.encode(program.seo, CHARSET)));
                    programPage.setTitle(program.title);
                    letterPage.getPages().add(programPage);
                    Collections.sort(letterPage.getPages(), new WebPageTitleComparator());
                }
            }
        }

        Collections.sort(root.getPages(), new WebPageTitleComparator());
        return root;
    }

    private Map<String, Map<String, List<Program>>> loadProgramStructure() throws IOException, JSONException {
        Map<String, String> header = HttpUtils.createFirefoxHeader();
        header.put("Accept", "application/json, text/plain, */*");
        header.put("Referer", BASE_URL + "/az");

        Map<String, Map<String, List<Program>>> programTree = new HashMap<String, Map<String, List<Program>>>();
        int pageNo = 1;
        int itemsLoaded = 0;
        int itemsPerPage = 500;
        int totalItems = -1;
        do {
            String url = PROGRAMS_URL + "&maxPerPage=" + itemsPerPage + "&page=" + pageNo;
            String response = HttpUtils.get(url, header, CHARSET);
            JSONObject json = new JSONObject(response);
            JSONArray items = json.getJSONArray("items");
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i);
                if(item.getBoolean("hasFreeEpisodes")) {
                    String station = item.getString("station");
                    String letter = item.getString("titleGroup");
                    Map<String, List<Program>> startLetters = programTree.get(station);
                    if(startLetters == null) {
                        startLetters = new HashMap<String, List<Program>>();
                        programTree.put(station, startLetters);
                    }
                    List<Program> programs = startLetters.get(letter);
                    if(programs == null) {
                        programs = new ArrayList<Program>();
                        startLetters.put(letter, programs);
                    }
                    String title = item.getString("title");
                    String seo = item.getString("seoUrl");
                    int id = item.getInt("id");
                    programs.add(new Program(id, title, seo));
                }
            }
            itemsLoaded += items.length();
            totalItems = json.getInt("total");
            pageNo++;
        } while(itemsLoaded < totalItems);
        return programTree;
    }

    @Override
    public String getTitle() {
        return "TV NOW";
    }

    @Override
    public IWebPage parse(IWebPage page) throws Exception {
        String uri = page.getUri().toString();
        if (page instanceof IVideoPage) {
            return page;
        } else if(uri.contains("/station/") || uri.contains("/letter/") || uri.contains("/season/")) {
            return page;
        } else if(uri.contains("/program/")) {
            parseProgramPage((IOverviewPage)page);
        }
        return page;
    }

    private void parseProgramPage(IOverviewPage opage) throws Exception {
        String pageUri = opage.getUri().toString();
        String seoUrl = pageUri.substring(pageUri.lastIndexOf('/')+1);
        String station = opage.getUri().getScheme();
        String uri = API_URL + "/formats/seo?fields=*,.*,formatTabs.*,formatTabs.formatTabPages.*,formatTabs.formatTabPages.container.*,formatTabs.formatTabPages.container.movies.*&name="+seoUrl+".php&station="+station;
        String response = HttpUtils.get(uri, null, CHARSET);
        JSONObject json = new JSONObject(response);
        JSONObject formatTabs = json.getJSONObject("formatTabs");
        JSONArray tabs = formatTabs.getJSONArray("items");
        for (int i = 0; i < tabs.length(); i++) {
            JSONObject season = tabs.getJSONObject(i);
            if(season.has("headline")) {
                String seasonTitle =  season.getString("headline");
                OverviewPage seasonPage = new OverviewPage();
                seasonPage.setParser(getId());
                seasonPage.setTitle(seasonTitle);
                seasonPage.setUri(new URI(station + "://season/" + URLEncoder.encode(seasonTitle, CHARSET)));
                opage.getPages().add(seasonPage);
                JSONObject formatTabPages = season.getJSONObject("formatTabPages");
                JSONArray subsections = formatTabPages.getJSONArray("items");
                for (int j = 0; j < subsections.length(); j++) {
                    JSONObject container = subsections.getJSONObject(j).getJSONObject("container");
                    if(container.has("movies") && !container.isNull("movies")) {
                        JSONArray movies = container.getJSONObject("movies").getJSONArray("items");
                        for (int k = 0; k < movies.length(); k++) {
                            try {
                                JSONObject movie = movies.getJSONObject(k);
                                boolean free = movie.getBoolean("free");
                                VideoPage video = new VideoPage();
                                video.setParser(getId());
                                video.setTitle(movie.getString("title") + (free ? " (frei)" : " (plus)"));
                                video.setUri(new URI(movie.getString("deeplinkUrl")));
                                video.setDescription(movie.getString("articleShort"));
                                video.setThumbnail(new URI("https://aistvnow-a.akamaihd.net/tvnow/movie/"+movie.getInt("replaceMovieInformation")+"/"));
                                video.setVideoUri(parseVideoUri(movie));
                                video.setDuration(parseDuration(movie));
                                video.setPublishDate(parsePublishDate(movie));
                                seasonPage.getPages().add(video);
                            } catch (Exception e) {
                                logger.log(LogService.LOG_ERROR, "Error while parsing movie", e);
                            }
                        }
                    }
                }
            } else {
                System.out.println("Season without title");
            }
        }
    }

    private URI parseVideoUri(JSONObject movie) throws JSONException, URISyntaxException {
        JSONObject manifest = movie.getJSONObject("manifest");
        return new URI(manifest.getString("hlsfairplay"));
    }

    private Calendar parsePublishDate(JSONObject movie) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(0);
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            String dateString = movie.getString("broadcastStartDate");
            cal.setTime(sdf.parse(dateString));
        } catch (Exception e) {
            logger.log(LogService.LOG_WARNING, "Couldn't parse publish date", e);
        }
        return cal;
    }

    private long parseDuration(JSONObject movie) throws JSONException {
        try {
            String duration = movie.getString("duration");
            Pattern pattern = Pattern.compile("(\\d{2}):(\\d{2}):(\\d{2})");
            Matcher matcher = pattern.matcher(duration);
            if(matcher.matches()) {
                int h = Integer.parseInt(matcher.group(1));
                int m = Integer.parseInt(matcher.group(2));
                int s = Integer.parseInt(matcher.group(3));
                return h * 3600 + m * 60 + s;
            }
        } catch (Exception e) {
            logger.log(LogService.LOG_WARNING, "Couldn't parse duration", e);
        }
        return 0;
    }

    @Override
    public String getId() {
        return ID;
    }

    private static class Program {
        @SuppressWarnings("unused")
        int id;
        String title;
        String seo;
        public Program(int id, String title, String seo) {
            super();
            this.id = id;
            this.title = title;
            this.seo = seo;
        }
    }
}