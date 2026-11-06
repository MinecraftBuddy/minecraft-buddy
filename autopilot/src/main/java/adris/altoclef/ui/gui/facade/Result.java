package adris.altoclef.ui.gui.facade;

// what an action hands back to the gui. nothing in the facade throws: a failure is a Result with ok false and a
// message a player can act on. command is the exact chat line that ran (or would have), shown small under the toast
public record Result(boolean ok, String message, String command) {

    public static Result ok(String message, String command) {
        return new Result(true, message, command);
    }

    public static Result ok(String message) {
        return new Result(true, message, null);
    }

    public static Result fail(String message) {
        return new Result(false, message, null);
    }

    public static Result fail(String message, String command) {
        return new Result(false, message, command);
    }
}
