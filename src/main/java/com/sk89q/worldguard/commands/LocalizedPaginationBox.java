package com.sk89q.worldguard.commands;

import com.sk89q.worldedit.util.formatting.component.InvalidComponentException;
import com.sk89q.worldedit.util.formatting.component.MessageBox;
import com.sk89q.worldedit.util.formatting.component.TextComponentProducer;
import com.sk89q.worldedit.util.formatting.text.Component;
import com.sk89q.worldedit.util.formatting.text.TextComponent;
import com.sk89q.worldguard.bukkit.BukkitMessages;

public abstract class LocalizedPaginationBox extends MessageBox {

    private final String pageCommand;
    private final String emptyMessage;
    private TextComponent heading;
    private int componentsPerPage = 8;
    private int currentPage = -1;

    protected LocalizedPaginationBox(String title, String pageCommand) {
        this(title, pageCommand, "@wg:paginationEmpty@");
    }

    protected LocalizedPaginationBox(String title, String pageCommand, String emptyMessage) {
        super(title, new TextComponentProducer());
        this.pageCommand = pageCommand;
        this.emptyMessage = emptyMessage;
    }

    public abstract Component getComponent(int number);

    public abstract int getComponentsSize();

    public void setComponentsPerPage(int componentsPerPage) {
        if (componentsPerPage < 1) throw new IllegalArgumentException("componentsPerPage must be positive");
        this.componentsPerPage = componentsPerPage;
    }

    protected final int getCurrentPage() {
        return currentPage;
    }

    @SuppressWarnings("deprecation")
    public Component create(int page) throws InvalidComponentException {
        int size = getComponentsSize();
        if (page == 1 && size == 0) return TextComponent.of(emptyMessage);
        int pageCount = (int) Math.ceil(size / (double) componentsPerPage);
        if (page < 1 || page > pageCount) {
            throw new InvalidComponentException(BukkitMessages.template(
                    "paginationInvalidPage", "page", page, "maximum", pageCount));
        }
        if (heading == null) heading = super.create();
        currentPage = page;
        getContents().reset();
        int end = Math.min(page * componentsPerPage, size);
        for (int index = (page - 1) * componentsPerPage; index < end; index++) {
            getContents().append(getComponent(index));
            if (index + 1 < end) getContents().newline();
        }
        if (pageCount > 1) {
            getContents().newline();
            getContents().append(centerAndBorder(navigation(page, pageCount)));
        }
        return heading.append(getContents().create());
    }

    private TextComponent navigation(int page, int pageCount) {
        TextComponent.Builder navigation = TextComponent.builder("");
        if (page > 1) navigation.append(pageButton("paginationPrevious", page - 1));
        navigation.append(TextComponent.of(BukkitMessages.template(
                "paginationPage", "page", page, "maximum", pageCount)));
        if (page < pageCount) navigation.append(pageButton("paginationNext", page + 1));
        return navigation.build();
    }

    private TextComponent pageButton(String key, int page) {
        return TextComponent.of(BukkitMessages.template(
                key, "command", pageCommand.replace("%page%", String.valueOf(page))));
    }
}
