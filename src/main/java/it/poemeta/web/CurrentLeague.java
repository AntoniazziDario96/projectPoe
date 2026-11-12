package it.poemeta.web;

import it.poemeta.economy.EconomyService;
import it.poemeta.league.League;
import it.poemeta.league.LeagueService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.util.Optional;

/**
 * Determina la lega scelta (parametro ?league=, poi cookie, poi default) e la mette
 * nel model di ogni pagina insieme all'elenco delle leghe e al prezzo della divine.
 */
@ControllerAdvice
public class CurrentLeague {

    private static final String COOKIE = "league";
    private static final String ATTR = CurrentLeague.class.getName();

    private final LeagueService leagues;
    private final EconomyService economy;

    public CurrentLeague(LeagueService leagues, EconomyService economy) {
        this.leagues = leagues;
        this.economy = economy;
    }

    @ModelAttribute
    public void populate(HttpServletRequest req, HttpServletResponse res, Model model) {
        League league = resolve(req, res);
        model.addAttribute("leagues", leagues.all());
        model.addAttribute("league", league);
        Double divine = league != null && league.hasEconomy()
                ? economy.divinePrice(league.economyName()).orElse(null) : null;
        model.addAttribute("divinePrice", divine);
    }

    /** La lega corrente per questa richiesta (può essere null se poe.ninja non risponde). */
    public League get(HttpServletRequest req) {
        Object v = req.getAttribute(ATTR);
        return v instanceof League l ? l : resolve(req, null);
    }

    private League resolve(HttpServletRequest req, HttpServletResponse res) {
        Object cached = req.getAttribute(ATTR);
        if (cached instanceof League l) {
            return l;
        }
        Optional<League> chosen = leagues.byUrl(req.getParameter("league"));
        if (chosen.isPresent() && res != null) {
            Cookie c = new Cookie(COOKIE, chosen.get().url());
            c.setPath("/");
            c.setMaxAge(60 * 60 * 24 * 365);
            res.addCookie(c);
        }
        if (chosen.isEmpty() && req.getCookies() != null) {
            for (Cookie c : req.getCookies()) {
                if (COOKIE.equals(c.getName())) {
                    chosen = leagues.byUrl(c.getValue());
                }
            }
        }
        League league = chosen.or(leagues::defaultLeague).orElse(null);
        if (league != null) {
            req.setAttribute(ATTR, league);
        }
        return league;
    }
}
