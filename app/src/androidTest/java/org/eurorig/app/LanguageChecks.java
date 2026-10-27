package org.eurorig.app;

import android.app.Instrumentation;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.widget.TextView;
import org.eurorig.routing.Truck;
import org.json.JSONObject;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Exercises the supported UI locale path and reports installed offline TTS voices on-device. */
final class LanguageChecks {
    private LanguageChecks(){}

    static String run(Instrumentation test)throws Exception{
        Context context=test.getTargetContext();
        NativeInterfaceChecks.require(context.getPackageName().endsWith(".preview"),"Localization checks require isolated Preview");
        SharedPreferences preferences=context.getSharedPreferences("settings",0);
        boolean hadLanguage=preferences.contains("app_language");int oldLanguage=preferences.getInt("app_language",0);
        boolean hadVoice=preferences.contains("voice");boolean oldVoice=preferences.getBoolean("voice",true);
        boolean hadVoiceLanguage=preferences.contains("voice_language");int oldVoiceLanguage=preferences.getInt("voice_language",0);
        MainActivity activity=null;
        JSONObject result=new JSONObject();
        try{
            preferences.edit().putBoolean("voice",false).putInt("voice_language",1).putInt("app_language",1).commit();
            activity=open(test,context);
            NativeInterfaceChecks.require(activity.getResources().getConfiguration().getLocales().get(0).getLanguage().equals("en"),"English app-language selection configures Activity resources");
            NativeInterfaceChecks.require(activity.getString(R.string.voice_settings_summary).equals("Offline instructions and audio output")&&activity.getString(R.string.voice_bluetooth_detail).equals("Pair or select audio output using Android")&&activity.getString(R.string.voice_bluetooth_unavailable).equals("Bluetooth settings are unavailable on this device."),"English voice settings and Bluetooth output copy resolves");
            NativeInterfaceChecks.require(activity.getString(R.string.voice_instructions_detail).startsWith("Speak turns")&&activity.getString(R.string.voice_installed_detail).startsWith("Manage offline voices")&&activity.getString(R.string.action_back).equals("Back"),"English voice details and shared back accessibility label resolve");
            NativeInterfaceChecks.require(activity.getString(R.string.screen_title_edit_route).equals("Edit route"),"English route resource resolves");
            NativeInterfaceChecks.require(activity.getString(R.string.route_same_endpoint_title).equals("Choose at least two different locations")&&activity.getString(R.string.route_waiting_gps_origin).contains("fresh, accurate")&&activity.getString(R.string.navigation_route_start_too_far).contains("250 m")&&activity.getString(R.string.navigation_precise_location_guidance_required).equals("Allow precise location to start guidance.")&&activity.getString(R.string.error_no_further_details).equals("No further details are available.")&&activity.getString(R.string.navigation_reroute_unavailable).startsWith("Rerouting is unavailable"),"English route-origin, reroute, error fallback and precise-location controls resolve");
            NativeInterfaceChecks.require(activity.getString(R.string.location_permission_required).contains("tap to enable")&&activity.getString(R.string.location_waiting_clear_sky).contains("clear view of the sky"),"English location permission and freshness status resolves");
            NativeInterfaceChecks.require(activity.getString(R.string.voice_language_requires_replan).equals("Stop guidance and clear the current route before changing the maneuver and speech language."),"English route-language guard resolves");
            Context roVoiceContext=NavigationService.speechLocaleContext(activity,"ro");
            NativeInterfaceChecks.require(NavigationService.authorisationSpeech(roVoiceContext).startsWith("Declarația privind permisiunea"),"Authorization warning speech follows Romanian voice with English app UI");
            NativeInterfaceChecks.require(activity.getString(R.string.search_map_warning).equals("Mapped places do not confirm truck entrances or legal access. Verify access with the site operator."),"English search warning preserves the map-only and operator-verification limitation");
            NativeInterfaceChecks.require(activity.getString(R.string.action_search).equals("Search")&&activity.getString(R.string.search_accessibility).contains("coordinates")&&activity.getString(R.string.simulation_speed_label,50).equals("Simulation speed · 50 km/h (0–200)")&&activity.getString(R.string.simulation_speed_accessibility).contains("kilometres per hour")&&activity.getString(R.string.route_planner_title).equals("Route planner")&&activity.getString(R.string.place_selected).equals("Selected place")&&activity.getString(R.string.action_get_directions).equals("Get directions")&&activity.getString(R.string.route_endpoints_accessibility).contains("destination")&&activity.getResources().getQuantityString(R.plurals.status_mapped_places,1,1).equals("1 mapped place")&&activity.getResources().getQuantityString(R.plurals.status_mapped_places,3,3).equals("3 mapped places"),"English search, route, simulation and mapped-result labels resolve");
            NativeInterfaceChecks.require(activity.getString(R.string.map_coverage_warning).contains("not proof of complete coverage or legal access")&&activity.getString(R.string.about_message).contains("This development build has not passed road validation."),"English map and About disclaimers preserve safety limits");
            NativeInterfaceChecks.require(activity.getString(R.string.navigation_speech_now_prefix).equals("Now,")&&String.format(java.util.Locale.ROOT,activity.getString(R.string.navigation_speech_distance_prefix),150).equals("In 150 metres,"),"English maneuver speech prefixes resolve with distance");
            NativeInterfaceChecks.require(activity.getString(R.string.navigation_mapped_speed_limit,"80 km/h").equals("Mapped speed limit 80 km/h")&&activity.getString(R.string.navigation_lane_no_permitted).startsWith("No verified permitted"),"English mapped speed and cautious lane fallback resolve");
            requireProfileIssueCopy(activity,"Saved truck profile is invalid.","Offline routing supports loaded gross weights up to 100 tonnes.","loaded gross mass 10.0 t / permitted maximum mass 20.0 t");
            NativeInterfaceChecks.require(activity.getString(R.string.profile_limits).contains("axle load/count")&&activity.getString(R.string.profile_explanation).contains("Actual loaded specifications"),"English truck-profile support limits and loaded-state explanation resolve");
            NativeInterfaceChecks.require(activity.getString(R.string.route_settings_title).equals("Route & Navigation")&&activity.getString(R.string.route_computing_title).equals("Route computing")&&activity.getString(R.string.routing_mode_economical).equals("Economical")&&activity.getString(R.string.route_row_accessibility,"Driving mode","3D · course up").equals("Driving mode: 3D · course up"),"English route and navigation settings labels resolve with their selected value");
            NativeInterfaceChecks.require(activity.getString(R.string.settings_notifications_detail).startsWith("Upcoming road conditions")&&activity.getString(R.string.settings_alert_evidence).equals("Use recorded map evidence.")&&activity.getString(R.string.settings_traffic_unavailable).contains("no live traffic provider"),"English notification labels disclose recorded evidence and traffic limits");
            NativeInterfaceChecks.require(activity.getString(R.string.settings_notifications_switch_detail).contains("foreground navigation notification remains available"),"English road-alert switch discloses the persistent Android navigation notification");
            NativeInterfaceChecks.require(activity.getString(R.string.route_lane_simple).equals("Simple lane assist")&&activity.getString(R.string.route_lane_dynamic_detail).startsWith("Animated perspective view")&&activity.getString(R.string.route_lane_early_detail).contains("5 km ahead")&&activity.getString(R.string.route_lane_early_detail).contains("from the right"),"English lane guidance options and early-preparation limits resolve");
            NativeInterfaceChecks.require(activity.getString(R.string.route_truck_lane_detail).contains("rightmost lane")&&activity.getString(R.string.route_right_turns_detail).startsWith("Directional turn preference is pending")&&activity.getString(R.string.route_right_turns_detail).contains("enforced in every route mode"),"English truck-lane and pending turn preference wording stays precise");
            requireArrivalCopy(activity,"Route ends 1112 m from the requested destination. Final access has not been verified.","Route ends at the requested destination; final access is admitted in routing based on your delivery or private-property permission declaration, without confirmation in property records.","Route ends at the requested destination, but the final access section could not be verified.","Route ends at the requested destination.");
            String englishQualified=ArrivalPresentation.routeText(activity,org.eurorig.routing.Arrival.describe(0,0,0,0,org.eurorig.routing.Arrival.Certainty.UNVERIFIED),"turn evidence unavailable");
            NativeInterfaceChecks.require(englishQualified.contains("could not be verified")&&englishQualified.contains("turn evidence unavailable"),"Route warning does not suppress final-access qualification");
            requireRouteSectionCopy(activity,"Access-restricted section","A restriction that may limit this vehicle is recorded here but cannot be evaluated (conditional, unparsable or unsupported). The route does not confirm or rule out its applicability; the app does not enforce it during route calculation.","Route with restricted sections. Tap for details.","Turn restrictions are checked by the routing engine only; this map has no independent turn-relation evidence.; This delivery road has no mapped restriction evidence.");
            NativeInterfaceChecks.require(RouteWarningPresentation.text(activity,"New map evidence").equals(activity.getString(R.string.route_warning_source_note,"New map evidence")),"Unknown English map warning is explicitly marked as source-language evidence");
            NativeInterfaceChecks.require(InterfacePages.offlineVoice(java.util.Collections.emptySet(),"en")==null&&activity.getString(R.string.voice_no_offline_pack).contains("Visual guidance remains available"),"Missing English voice resolves to disclosed visual-only guidance");
            requireVoiceStatus(test,activity,"en",R.string.voice_offline_installed);
            activity.finish();activity=null;test.waitForIdleSync();

            preferences.edit().putInt("app_language",2).putInt("voice_language",2).commit();
            activity=open(test,context);
            NativeInterfaceChecks.require(activity.getResources().getConfiguration().getLocales().get(0).getLanguage().equals("ro"),"Romanian app-language selection configures Activity resources");
            NativeInterfaceChecks.require(activity.getString(R.string.screen_title_edit_route).equals("Editare traseu"),"Romanian route resource resolves");
            NativeInterfaceChecks.require(activity.getString(R.string.navigation_mapped_speed_limit,"80 km/h").startsWith("Limită de viteză cartografiată")&&activity.getString(R.string.navigation_lane_no_permitted).startsWith("Nu există o recomandare verificată"),"Romanian mapped speed and cautious lane fallback resolve");
            NativeInterfaceChecks.require(activity.getString(R.string.search_map_warning).equals("Locurile de pe hartă nu confirmă intrările pentru camioane sau accesul legal. Verificați accesul cu operatorul obiectivului."),"Romanian search warning preserves the map-only and operator-verification limitation");
            NativeInterfaceChecks.require(activity.getString(R.string.voice_settings_title).equals("Limbă și voce"),"Romanian voice-settings resource resolves");
            NativeInterfaceChecks.require(activity.getString(R.string.voice_settings_summary).equals("Instrucțiuni offline și ieșire audio")&&activity.getString(R.string.voice_bluetooth_detail).startsWith("Împerecheați sau alegeți")&&activity.getString(R.string.voice_bluetooth_unavailable).equals("Setările Bluetooth nu sunt disponibile pe acest dispozitiv."),"Romanian voice settings and Bluetooth output copy resolves");
            NativeInterfaceChecks.require(activity.getString(R.string.voice_instructions_detail).startsWith("Rostiți manevrele")&&activity.getString(R.string.voice_installed_detail).startsWith("Gestionați vocile")&&activity.getString(R.string.action_back).equals("Înapoi"),"Romanian voice details and shared back accessibility label use formal address");
            NativeInterfaceChecks.require(activity.getString(R.string.voice_language_requires_replan).equals("Opriți ghidarea și anulați traseul curent înainte de a schimba limba manevrelor și a indicațiilor vocale."),"Romanian route-language guard resolves");
            Context enVoiceContext=NavigationService.speechLocaleContext(activity,"en");
            NativeInterfaceChecks.require(NavigationService.authorisationSpeech(enVoiceContext).startsWith("The route access declaration"),"Authorization warning speech follows English voice with Romanian app UI");
            NativeInterfaceChecks.require(activity.getString(R.string.download_country_start).equals("Descărcați o țară pentru a începe")&&activity.getString(R.string.search_minimum).equals("Introduceți cel puțin două caractere"),"Romanian setup and search labels resolve");
            NativeInterfaceChecks.require(activity.getString(R.string.action_search).equals("Căutare")&&activity.getString(R.string.search_accessibility).contains("coordonate")&&activity.getString(R.string.simulation_speed_label,50).equals("Viteză simulare · 50 km/h (0–200)")&&activity.getString(R.string.simulation_speed_accessibility).contains("kilometri pe oră")&&activity.getString(R.string.route_planner_title).equals("Planificator de traseu")&&activity.getString(R.string.place_selected).equals("Loc selectat")&&activity.getString(R.string.action_get_directions).equals("Obțineți indicații")&&activity.getString(R.string.route_endpoints_accessibility).contains("destinația")&&activity.getResources().getQuantityString(R.plurals.status_mapped_places,1,1).equals("1 loc cartografiat")&&activity.getResources().getQuantityString(R.plurals.status_mapped_places,3,3).equals("3 locuri cartografiate"),"Romanian search, route, simulation and mapped-result labels resolve");
            NativeInterfaceChecks.require(activity.getString(R.string.map_coverage_warning).contains("nu înseamnă")&&activity.getString(R.string.map_coverage_warning).contains("accesul legal")&&activity.getString(R.string.about_message).contains("nu a fost validată în trafic"),"Romanian map and About disclaimers preserve safety limits");
            NativeInterfaceChecks.require(activity.getString(R.string.action_route_access_permissions).equals("Calcularea traseului și permisiuni de acces")&&activity.getString(R.string.action_calculate_route).equals("Calculați traseul")&&activity.getString(R.string.action_update_route).equals("Actualizați traseul"),"Romanian route controls resolve");
            NativeInterfaceChecks.require(activity.getString(R.string.route_same_endpoint_title).equals("Alegeți cel puțin două locații diferite")&&activity.getString(R.string.route_waiting_gps_origin).contains("actualizată și precisă")&&activity.getString(R.string.navigation_route_start_too_far).contains("250 m")&&activity.getString(R.string.navigation_precise_location_guidance_required).equals("Permiteți accesul la locația precisă pentru a porni ghidarea.")&&activity.getString(R.string.error_no_further_details).equals("Nu sunt disponibile alte detalii.")&&activity.getString(R.string.navigation_reroute_unavailable).startsWith("Recalcularea traseului"),"Romanian route-origin, reroute, error fallback and precise-location controls resolve");
            NativeInterfaceChecks.require(activity.getString(R.string.location_permission_required).contains("permisiunea pentru locație")&&activity.getString(R.string.location_waiting_clear_sky).contains("neobstrucționată spre cer"),"Romanian location permission and freshness status resolves");
            NativeInterfaceChecks.require(activity.getString(R.string.navigation_speech_now_prefix).equals("Acum,")&&String.format(java.util.Locale.ROOT,activity.getString(R.string.navigation_speech_distance_prefix),150).equals("În 150 de metri,"),"Romanian maneuver speech prefixes resolve with distance");
            requireProfileIssueCopy(activity,"Profilul camionului salvat este nevalid.","Rutarea offline acceptă mase totale încărcate de cel mult 100 de tone.","masă totală încărcată 10.0 t / masă maximă autorizată 20.0 t");
            NativeInterfaceChecks.require(activity.getString(R.string.profile_limits).contains("sarcina pe osie")&&activity.getString(R.string.profile_limits).contains("numărul total de osii")&&activity.getString(R.string.profile_explanation).equals("Datele reale ale vehiculului în stare încărcată ghidează planificarea traseului."),"Romanian truck-profile limits distinguish axle load from total axle count and loaded state");
            NativeInterfaceChecks.require(activity.getString(R.string.navigation_menu_title).equals("Meniu")&&activity.getString(R.string.navigation_menu_profile_summary,4.0,18.0).contains("18,0 t încărcate")&&activity.getString(R.string.settings_map_title).equals("Hartă și unități de măsură")&&activity.getString(R.string.settings_appearance_dark).contains("negru OLED"),"Romanian menu and settings entry points resolve");
            NativeInterfaceChecks.require(activity.getString(R.string.route_settings_title).equals("Traseu și navigare")&&activity.getString(R.string.route_computing_title).equals("Calculul traseului")&&activity.getString(R.string.routing_mode_economical).equals("Economic")&&activity.getString(R.string.route_row_accessibility,"Mod de condus","3D · orientat după direcția de mers").equals("Mod de condus: 3D · orientat după direcția de mers"),"Romanian route and navigation settings labels resolve with their selected value");
            NativeInterfaceChecks.require(activity.getString(R.string.route_lane_simple).equals("Asistență simplă pe benzi")&&activity.getString(R.string.route_lane_none).equals("Niciuna")&&activity.getString(R.string.route_eta_to_waypoint).equals("Afișați informațiile până la următorul punct intermediar")&&activity.getString(R.string.route_lane_early_detail).contains("5 km")&&activity.getString(R.string.route_lane_early_detail).contains("de la dreapta"),"Romanian lane guidance options and early-preparation limits resolve");
            NativeInterfaceChecks.require(activity.getString(R.string.route_truck_lane_detail).startsWith("Preferați banda din dreapta")&&activity.getString(R.string.route_right_turns_title).contains("Regatul Unit")&&activity.getString(R.string.route_right_turns_detail).contains("în curs de implementare")&&activity.getString(R.string.route_right_turns_detail).contains("rămân aplicate în orice mod de traseu"),"Romanian truck-lane and pending turn preference wording stays precise");
            NativeInterfaceChecks.require(activity.getString(R.string.settings_notifications_detail).startsWith("Alerte despre condițiile rutiere")&&activity.getString(R.string.settings_alert_evidence).startsWith("Folosiți dovezile")&&activity.getString(R.string.settings_traffic_unavailable).contains("în timp real"),"Romanian notification safety labels and traffic limitations resolve");
            NativeInterfaceChecks.require(activity.getString(R.string.settings_notifications_switch_detail).contains("Notificarea Android")&&activity.getString(R.string.settings_notifications_switch_detail).contains("rămâne disponibilă"),"Romanian road-alert switch discloses the persistent Android navigation notification");
            requireArrivalCopy(activity,"Traseul se încheie la 1112 m de destinația solicitată. Accesul final nu a fost verificat.","Traseul se încheie la destinația solicitată; accesul final este admis în calculul traseului pe baza permisiunii de acces pentru livrare sau pe proprietate privată declarate de dumneavoastră, fără confirmare în evidențele privind proprietatea.","Traseul se încheie la destinația solicitată, dar porțiunea finală de acces nu a putut fi verificată.","Traseul se încheie la destinația solicitată.");
            String romanianQualified=ArrivalPresentation.routeText(activity,org.eurorig.routing.Arrival.describe(0,0,0,0,org.eurorig.routing.Arrival.Certainty.UNVERIFIED),"turn evidence unavailable");
            NativeInterfaceChecks.require(romanianQualified.startsWith("Traseul se încheie")&&romanianQualified.contains("turn evidence unavailable"),"Romanian route warning does not suppress final-access qualification");
            requireRouteSectionCopy(activity,"Sector cu acces restricționat","Pe acest sector este înregistrată o restricție care poate limita circulația acestui vehicul, dar pe care aplicația nu o poate evalua (condițională, imposibil de interpretat sau fără suport în aplicație). Traseul nu confirmă și nu infirmă aplicabilitatea acesteia; aplicația nu o impune la calcularea traseului.","Traseul conține sectoare cu restricții. Atingeți pentru detalii.","Restricțiile de viraj sunt verificate numai de motorul de rutare; această hartă nu conține dovezi independente privind relațiile de viraj.; Acest drum pentru livrare nu are dovezi cartografiate privind restricțiile.");
            NativeInterfaceChecks.require(RouteWarningPresentation.text(activity,"New map evidence").equals(activity.getString(R.string.route_warning_source_note,"New map evidence")),"Unknown Romanian map warning is explicitly marked as source-language evidence");
            String endpoints=String.format(java.util.Locale.ROOT,activity.getString(R.string.route_endpoints),"OTP","LTN");
            NativeInterfaceChecks.require(endpoints.contains("OTP")&&endpoints.contains("LTN")&&endpoints.indexOf("OTP")<endpoints.indexOf("LTN"),"Romanian route endpoint format preserves both arguments in order");
            NativeInterfaceChecks.require(InterfacePages.offlineVoice(java.util.Collections.emptySet(),"ro")==null&&activity.getString(R.string.voice_no_offline_pack).contains("Ghidarea vizuală rămâne disponibilă"),"Missing Romanian voice resolves to disclosed visual-only guidance");
            requireVoiceStatus(test,activity,"ro",R.string.voice_offline_installed);
            NativeInterfaceChecks.require(activity.getString(R.string.delivery_permission).equals("Am permisiunea pentru accesul restricționat aferent acestei livrări, în apropierea destinației."),"Romanian access-consent wording preserves the driver's delivery-specific permission declaration");
            result.put("englishUi","PASS").put("romanianUi","PASS").put("unreviewedSafetyFallback","PASS");
            NativeInterfaceChecks.require(InterfacePages.voiceLanguageChangeBlocked(true,1,2),"Changing language while a route exists is blocked");
            NativeInterfaceChecks.require(!InterfacePages.voiceLanguageChangeBlocked(true,1,1)&&!InterfacePages.voiceLanguageChangeBlocked(false,1,2),"Unchanged selection and route-free language changes remain available");
            result.put("routeLanguageGuard","PASS");
            activity.finish();activity=null;test.waitForIdleSync();
            result.put("offlineVoices",probeVoices(context));
            return result.toString();
        }finally{
            if(activity!=null)activity.finish();
            test.waitForIdleSync();
            SharedPreferences.Editor restore=preferences.edit();
            if(hadLanguage)restore.putInt("app_language",oldLanguage);else restore.remove("app_language");
            if(hadVoice)restore.putBoolean("voice",oldVoice);else restore.remove("voice");
            if(hadVoiceLanguage)restore.putInt("voice_language",oldVoiceLanguage);else restore.remove("voice_language");
            restore.commit();
        }
    }

    private static void requireVoiceStatus(Instrumentation test,MainActivity activity,String language,int expected)throws Exception{
        InterfacePages pages=(InterfacePages)NativeInterfaceChecks.field(activity,"pages");
        NativeInterfaceChecks.ui(test,()->NativeInterfaceChecks.invoke(pages,"voiceSettings"));
        Dialog dialog=(Dialog)NativeInterfaceChecks.field(pages,"current");
        TextView status=dialog.getWindow().getDecorView().findViewWithTag("offline-voice-status");
        NativeInterfaceChecks.require(status!=null,"Offline voice status is visible in voice settings");
        NativeInterfaceChecks.await(test,()->!status.getText().toString().equals(activity.getString(R.string.voice_checking_offline)),30000,"Offline voice availability check finishes for "+language);
        NativeInterfaceChecks.require(status.getText().toString().equals(activity.getString(expected)),"Voice settings report offline "+language+" availability");
    }

    private static void requireArrivalCopy(MainActivity activity,String shortfall,String admitted,String unverified,String ordinary){
        java.util.Locale locale=activity.getResources().getConfiguration().getLocales().get(0);
        String formatted=String.format(locale,activity.getString(R.string.navigation_arrival_shortfall),org.eurorig.routing.Geo.distance(0,0,0,0.01));
        NativeInterfaceChecks.require(formatted.equals(shortfall),"Arrival shortfall includes a formatted distance and access uncertainty");
        NativeInterfaceChecks.require(ArrivalPresentation.text(activity,org.eurorig.routing.Arrival.describe(0,0,0,0,org.eurorig.routing.Arrival.Certainty.ADMITTED_ACCESS)).equals(admitted),"Admitted arrival retains its permission qualification");
        NativeInterfaceChecks.require(ArrivalPresentation.text(activity,org.eurorig.routing.Arrival.describe(0,0,0,0,org.eurorig.routing.Arrival.Certainty.UNVERIFIED)).equals(unverified),"Unverified approach qualification is preserved at zero offset");
        NativeInterfaceChecks.require(ArrivalPresentation.text(activity,org.eurorig.routing.Arrival.describe(0,0,0,0,org.eurorig.routing.Arrival.Certainty.VERIFIED)).equals(ordinary),"Ordinary route end is localized");
    }

    private static void requireRouteSectionCopy(MainActivity activity,String accessLabel,String uncertainExplanation,String summaryDescription,String translatedWarnings){
        NativeInterfaceChecks.require(RouteSectionPresentation.label(activity,org.eurorig.routing.RouteSection.AUTHORISED_ACCESS,org.eurorig.routing.RouteSection.POTENTIAL_LIMIT).equals(accessLabel),"Access-restricted route section label resolves in the selected locale");
        NativeInterfaceChecks.require(RouteSectionPresentation.explanation(activity,org.eurorig.routing.RouteSection.UNVERIFIED,org.eurorig.routing.RouteSection.POTENTIAL_LIMIT).equals(uncertainExplanation),"Unverified route explanation resolves without changing its meaning");
        NativeInterfaceChecks.require(RouteSectionPresentation.explanation(activity,org.eurorig.routing.RouteSection.AUTHORISED_ACCESS,org.eurorig.routing.RouteSection.POTENTIAL_LIMIT).equals(activity.getString(R.string.route_section_authorised_explanation)),"Access-admitted section uses reviewed authorization conditions");
        NativeInterfaceChecks.require(RouteSectionPresentation.explanation(activity,org.eurorig.routing.RouteSection.PROHIBITED,org.eurorig.routing.RouteSection.POTENTIAL_LIMIT).equals(activity.getString(R.string.route_section_prohibited_explanation)),"Prohibited route detail resolves in the selected locale");
        NativeInterfaceChecks.require(RouteSectionPresentation.label(activity,org.eurorig.routing.RouteSection.UNVERIFIED,org.eurorig.routing.RouteSection.ADVISORY).equals(activity.getString(R.string.route_section_advisory)),"Advisory route label resolves in the selected locale");
        NativeInterfaceChecks.require(activity.getString(R.string.route_summary_restrictions_content_description).equals(summaryDescription),"Restricted-route detail affordance is localized");
        NativeInterfaceChecks.require(RouteWarningPresentation.text(activity,"Turn restrictions checked by the native engine only; independent relation evidence is unavailable in this map; Delivery road has no restriction evidence").equals(translatedWarnings),"Known route audit warnings resolve in the selected locale");
    }

    private static void requireProfileIssueCopy(MainActivity activity,String reviewPrefix,String weightPrefix,String massSummary)throws Exception{
        boolean previous=Store.truckNeedsReview;
        try{
            NativeInterfaceChecks.require(activity.getString(R.string.profile_axle).contains(activity.getResources().getConfiguration().getLocales().get(0).getLanguage().equals("ro")?"în stare încărcată":"loaded")&&activity.getString(R.string.profile_mass_exceeds_permitted).contains(activity.getResources().getConfiguration().getLocales().get(0).getLanguage().equals("ro")?"Masa totală încărcată":"Loaded gross mass"),"Axle and gross-mass labels preserve loaded-state meaning in the selected locale");
            Store.truckNeedsReview=true;
            NativeInterfaceChecks.require(ProfileIssuePresentation.text(activity,Truck.standard(),Store.TRUCK_REVIEW).startsWith(reviewPrefix),"Invalid saved truck profile refusal resolves in the selected locale");
            Store.truckNeedsReview=false;
            Truck heavy=new Truck(4,2.55,16.5,120,11.5,false,false,true,true);
            String issue=NativeRouter.profileSupportIssue(heavy);
            NativeInterfaceChecks.require(ProfileIssuePresentation.text(activity,heavy,issue).startsWith(weightPrefix),"Unsupported loaded weight refusal resolves in the selected locale");
            NativeInterfaceChecks.require(ProfileIssuePresentation.text(activity,Truck.standard(),issue).equals(issue)&&ProfileIssuePresentation.text(activity,heavy,"unrecognized profile issue").equals("unrecognized profile issue"),"Profile issue translation preserves mismatched and unknown source details");
            String formatted=String.format(java.util.Locale.ROOT,activity.getString(R.string.trip_truck_summary),"Truck",10.0,20.0,4.0,2.5,16.5,11.5,5,80.0,"none","none");
            NativeInterfaceChecks.require(formatted.contains(massSummary),"Interrupted-trip truck summary distinguishes loaded gross and permitted maximum mass");
        }finally{Store.truckNeedsReview=previous;}
    }

    private static MainActivity open(Instrumentation test,Context context){
        Intent intent=new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);
        return (MainActivity)test.startActivitySync(intent);
    }

    private static JSONObject probeVoices(Context context)throws Exception{
        CountDownLatch ready=new CountDownLatch(1);int[] status={TextToSpeech.ERROR};
        TextToSpeech engine=new TextToSpeech(context,state->{status[0]=state;ready.countDown();});
        try{
            NativeInterfaceChecks.require(ready.await(30,TimeUnit.SECONDS),"TTS engine initialization callback arrives");
            NativeInterfaceChecks.require(status[0]==TextToSpeech.SUCCESS,"TTS engine initializes");
            Voice english=null,romanian=null;
            for(Voice voice:engine.getVoices())if(!voice.isNetworkConnectionRequired()){
                String language=voice.getLocale().getLanguage();if(language.equals("en")&&english==null)english=voice;if(language.equals("ro")&&romanian==null)romanian=voice;
            }
            JSONObject result=new JSONObject()
                .put("englishOfflineInstalled",english!=null)
                .put("romanianOfflineInstalled",romanian!=null)
                .put("utteranceBasis","TTS engine completion callbacks; audible speaker output is not measured");
            result.put("englishUtterance",english==null?UNAVAILABLE_ENGLISH:speakCompleted(engine,english,"Turn left onto Strada Ștefan cel Mare, number 25."));
            result.put("romanianUtterance",romanian==null?UNAVAILABLE_ROMANIAN:speakCompleted(engine,romanian,"Virați la stânga pe strada Ștefan cel Mare, numărul 25."));
            Voice stoppable=english!=null?english:romanian;
            String interruptionText=stoppable==romanian?"Virați la stânga pe strada Ștefan cel Mare, numărul 25.":"Turn left onto Strada Ștefan cel Mare, number 25.";
            result.put("interruption",stoppable==null?UNAVAILABLE_VOICE:stopInterrupted(engine,stoppable,longUtterance(interruptionText)));
            result.put("serviceAudioFocusInterruption",stoppable==null?UNAVAILABLE_VOICE:stopForServiceAction(engine,stoppable,longUtterance(interruptionText),false));
            result.put("serviceMute",stoppable==null?UNAVAILABLE_VOICE:stopForServiceAction(engine,stoppable,longUtterance(interruptionText),true));
            return result;
        }finally{engine.stop();engine.shutdown();}
    }

    private static final String UNAVAILABLE_ENGLISH="UNAVAILABLE: no installed offline English voice";
    private static final String UNAVAILABLE_ROMANIAN="UNAVAILABLE: no installed offline Romanian voice";
    private static final String UNAVAILABLE_VOICE="UNAVAILABLE: no installed offline voice to interrupt";

    private static String longUtterance(String sentence){
        StringBuilder text=new StringBuilder();
        for(int i=0;i<12;i++)text.append(sentence).append(' ');
        return text.toString();
    }

    /** Requires onDone: an error or interruption callback fails the check, it does not pass it. */
    private static String speakCompleted(TextToSpeech engine,Voice voice,String text)throws Exception{
        NativeInterfaceChecks.require(engine.setVoice(voice)==TextToSpeech.SUCCESS,"Selected offline voice is accepted");
        CountDownLatch finished=new CountDownLatch(1);String[] event={"pending"};
        engine.setOnUtteranceProgressListener(new UtteranceProgressListener(){
            public void onStart(String id){}
            public void onDone(String id){event[0]="completed";finished.countDown();}
            public void onError(String id){event[0]="error";finished.countDown();}
            public void onError(String id,int code){event[0]="error:"+code;finished.countDown();}
            public void onStop(String id,boolean interrupted){event[0]=interrupted?"interrupted":"stopped";finished.countDown();}
        });
        NativeInterfaceChecks.require(engine.speak(text,TextToSpeech.QUEUE_FLUSH,null,"language-check")==TextToSpeech.SUCCESS,"Offline utterance is accepted");
        NativeInterfaceChecks.require(finished.await(30,TimeUnit.SECONDS),"Offline utterance callback arrives");
        NativeInterfaceChecks.require("completed".equals(event[0]),"Offline utterance reaches TTS engine completion (onDone), got "+event[0]);
        return "PASS:onDone (engine completion)";
    }

    /** Controlled interruption: speak a long utterance, stop it, require onStop(interrupted=true). */
    private static String stopInterrupted(TextToSpeech engine,Voice voice,String text)throws Exception{
        NativeInterfaceChecks.require(engine.setVoice(voice)==TextToSpeech.SUCCESS,"Selected offline voice is accepted");
        CountDownLatch started=new CountDownLatch(1);CountDownLatch stopped=new CountDownLatch(1);
        boolean[] interrupted={false};String[] early={"none"};
        engine.setOnUtteranceProgressListener(new UtteranceProgressListener(){
            public void onStart(String id){started.countDown();}
            public void onDone(String id){early[0]="completed";}
            public void onError(String id){early[0]="error";stopped.countDown();}
            public void onError(String id,int code){early[0]="error:"+code;stopped.countDown();}
            public void onStop(String id,boolean flag){interrupted[0]=flag;stopped.countDown();}
        });
        NativeInterfaceChecks.require(engine.speak(text,TextToSpeech.QUEUE_FLUSH,null,"interrupt-check")==TextToSpeech.SUCCESS,"Long offline utterance is accepted");
        NativeInterfaceChecks.require(started.await(30,TimeUnit.SECONDS),"Long offline utterance starts before it is stopped");
        Thread.sleep(150);
        NativeInterfaceChecks.require(engine.stop()==TextToSpeech.SUCCESS,"TTS engine accepts stop during playback");
        NativeInterfaceChecks.require(stopped.await(30,TimeUnit.SECONDS),"Interrupted utterance reports a terminal callback, got "+early[0]);
        NativeInterfaceChecks.require(interrupted[0],"TTS engine reports onStop(interrupted=true) instead of plain completion");
        return "PASS:onStop(interrupted=true)";
    }

    /** A focus-loss event in NavigationService stops the active utterance without replaying it. */
    private static String stopForServiceAction(TextToSpeech engine,Voice voice,String text,boolean mute)throws Exception{
        NativeInterfaceChecks.require(engine.setVoice(voice)==TextToSpeech.SUCCESS,"Selected offline voice is accepted for service interruption");
        CountDownLatch started=new CountDownLatch(1);CountDownLatch stopped=new CountDownLatch(1);boolean[] interrupted={false};
        engine.setOnUtteranceProgressListener(new UtteranceProgressListener(){
            public void onStart(String id){started.countDown();}
            public void onDone(String id){}
            public void onError(String id){stopped.countDown();}
            public void onError(String id,int code){stopped.countDown();}
            public void onStop(String id,boolean flag){interrupted[0]=flag;stopped.countDown();}
        });
        NativeInterfaceChecks.require(engine.speak(text,TextToSpeech.QUEUE_FLUSH,null,"service-focus-check")==TextToSpeech.SUCCESS,"Long utterance for service focus check is accepted");
        NativeInterfaceChecks.require(started.await(30,TimeUnit.SECONDS),"Service focus-check utterance starts");
        NavigationService service=new NavigationService();
        java.lang.reflect.Field speech=NavigationService.class.getDeclaredField("speech");speech.setAccessible(true);speech.set(service,engine);
        if(mute)service.muteSpeech();else{
            java.lang.reflect.Method focusChanged=NavigationService.class.getDeclaredMethod("audioFocusChanged",int.class);
            focusChanged.setAccessible(true);
            focusChanged.invoke(service,AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
        }
        NativeInterfaceChecks.require(stopped.await(30,TimeUnit.SECONDS)&&interrupted[0],mute?"Service mute stops active speech with interruption callback":"Service audio-focus loss stops active speech with interruption callback");
        return mute?"PASS:NavigationService mute stops active speech":"PASS:NavigationService AUDIOFOCUS_LOSS_TRANSIENT callback stops active speech";
    }
}
